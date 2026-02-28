package com.pocketpal.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val downloadDao = DownloadDatabase.getInstance(context).downloadDao()
    private val client = OkHttpClient.Builder()
        .addInterceptor(ProgressInterceptor())
        .build()
    private var lastProgressUpdate = 0L
    private var currentCall: Call? = null

    init {
        Log.d(TAG, "Initializing DownloadWorker")
    }

    private fun handleStopped() {
        Log.d(TAG, "Worker stopped, cancelling any ongoing network request")
        currentCall?.cancel()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return@withContext Result.failure()
            Log.d(TAG, "Starting download work for ID: $downloadId")

            if (isStopped) {
                Log.d(TAG, "Work was cancelled before starting for ID: $downloadId")
                handleStopped()
                downloadDao.updateStatus(downloadId, DownloadStatus.CANCELLED, "Download cancelled")
                return@withContext Result.failure()
            }

            val progressInterval = inputData.getLong(KEY_PROGRESS_INTERVAL, DEFAULT_PROGRESS_INTERVAL)
            Log.d(TAG, "Progress update interval: $progressInterval ms")

            val download = downloadDao.getDownload(downloadId) ?: return@withContext Result.failure()
            Log.d(TAG, "Retrieved download info: $download")

            if (download.status == DownloadStatus.PAUSED) {
                Log.d(TAG, "Download is paused, returning retry for ID: $downloadId")
                return@withContext Result.retry()
            }

            // Determine if destination is a SAF content:// URI or a regular file path
            val isSafUri = download.destination.startsWith("content://")
            Log.d(TAG, "Download destination: ${download.destination}, isSafUri=$isSafUri")

            // For regular file paths, use File API; for SAF URIs, use ContentResolver
            val file: File? = if (isSafUri) null else File(download.destination)
            val safUri: Uri? = if (isSafUri) Uri.parse(download.destination) else null

            if (file != null) {
                Log.d(TAG, "Download destination (file): ${file.absolutePath}")
            } else {
                Log.d(TAG, "Download destination (SAF URI): $safUri")
            }

            // Check if file size and database are in sync (only for regular files)
            if (file != null && file.exists() && file.length() > 0) {
                // If file exists but size doesn't match database, update database
                if (file.length() != download.downloadedBytes) {
                    Log.d(TAG, "File size (${file.length()}) doesn't match database (${download.downloadedBytes}). Updating database.")
                    downloadDao.updateProgress(downloadId, file.length(), download.totalBytes, download.status)
                    // Reload download info after update
                    val updatedDownload = downloadDao.getDownload(downloadId)
                    if (updatedDownload != null) {
                        Log.d(TAG, "Updated download info: $updatedDownload")
                    }
                }
            }
            // For SAF URIs, check existing size via ContentResolver
            if (safUri != null) {
                try {
                    val cursor = applicationContext.contentResolver.query(
                        safUri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (sizeIdx >= 0) {
                                val existingSize = it.getLong(sizeIdx)
                                if (existingSize > 0 && existingSize != download.downloadedBytes) {
                                    Log.d(TAG, "SAF file size ($existingSize) doesn't match database (${download.downloadedBytes}). Updating database.")
                                    downloadDao.updateProgress(downloadId, existingSize, download.totalBytes, download.status)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not query SAF file size", e)
                }
            }
            
            // Determine existing bytes for range request (resume support)
            val existingBytes = if (file != null && file.exists()) file.length() else download.downloadedBytes

            val request = Request.Builder()
                .url(download.url)
                .apply {
                    if (existingBytes > 0) {
                        val range = "bytes=$existingBytes-"
                        Log.d(TAG, "Resuming download from byte $existingBytes")
                        addHeader("Range", range)
                    }
                    
                    // Add authorization header if token is available
                    download.authToken?.let { token ->
                        Log.d(TAG, "Adding Authorization header for authenticated download")
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .build()

            Log.d(TAG, "Updating status to RUNNING for ID: $downloadId")
            downloadDao.updateStatus(downloadId, DownloadStatus.RUNNING)

            Log.d(TAG, "Executing network request for ID: $downloadId")
            val response = suspendCoroutine { continuation ->
                val call = client.newCall(request)
                currentCall = call  // Store the call reference
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (call.isCanceled()) {
                            Log.d(TAG, "Network request was cancelled for ID: $downloadId")
                        } else {
                            Log.e(TAG, "Network request failed for ID: $downloadId", e)
                        }
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        Log.d(TAG, "Received response for ID: $downloadId, code: ${response.code}")
                        continuation.resume(response)
                    }
                })
            }
            currentCall = null  // Clear the reference after completion

            if (existingBytes > 0 && response.code == 200) {
                Log.w(TAG, "Server ignored range request, returning full file. Restarting download from beginning.")
                if (file != null && file.exists()) {
                    Log.d(TAG, "Deleting partial file to restart download: ${file.absolutePath}")
                    file.delete()
                }
                // For SAF URIs, we can't easily truncate; just reset the byte counter
            } else if (!response.isSuccessful) {
                when (response.code) {
                    416 -> {
                        Log.e(TAG, "Server rejected the range request for ID: $downloadId")
                        
                        if (file != null && file.exists()) {
                            Log.d(TAG, "Deleting invalid partial file: ${file.absolutePath}")
                            file.delete()
                        }
                        
                        downloadDao.updateStatus(
                            downloadId,
                            DownloadStatus.FAILED,
                            "Download failed: The partial download was invalid or the file on server has changed"
                        )
                        
                        return@withContext Result.failure()
                    }
                    in 400..499 -> {
                        val error = "Client error: ${response.code}"
                        Log.e(TAG, error)
                        downloadDao.updateStatus(downloadId, DownloadStatus.FAILED, error)
                        return@withContext Result.failure()
                    }
                    in 500..599 -> {
                        val error = "Server error: ${response.code}"
                        Log.e(TAG, error)
                        downloadDao.updateStatus(downloadId, DownloadStatus.FAILED, error)
                        return@withContext Result.retry()
                    }
                    else -> {
                        val error = "Unexpected response: ${response.code}"
                        Log.e(TAG, error)
                        downloadDao.updateStatus(downloadId, DownloadStatus.FAILED, error)
                        return@withContext Result.failure()
                    }
                }
            }

            response.body?.let { body ->
                // Get content length from response
                val contentLength = body.contentLength()
                Log.d(TAG, "Content length from response: $contentLength bytes for ID: $downloadId")
                
                // Get existing bytes written
                var bytesWritten = if (file != null && file.exists()) file.length() else download.downloadedBytes
                Log.d(TAG, "Existing bytes written: $bytesWritten for ID: $downloadId")
                
                // Calculate total expected size based on response code
                val totalBytes = when (response.code) {
                    206 -> {
                        // For partial content (206), the content-length is just the remaining bytes
                        // So add existing bytes to get total size
                        val total = bytesWritten + contentLength
                        Log.d(TAG, "Partial content (206): Total size = $bytesWritten + $contentLength = $total bytes")
                        total
                    }
                    200 -> {
                        // For full content (200), use the content length as total size
                        Log.d(TAG, "Full content (200): Total size = $contentLength bytes")
                        contentLength
                    }
                    else -> {
                        // For other responses, use the larger of content length or existing total
                        val total = maxOf(contentLength, download.totalBytes)
                        Log.d(TAG, "Other response (${response.code}): Using total size = $total bytes")
                        total
                    }
                }
                
                // Update database with correct progress information
                downloadDao.updateProgress(downloadId, bytesWritten, totalBytes, DownloadStatus.RUNNING)
                Log.d(TAG, "Updated database: $bytesWritten/$totalBytes bytes (${(bytesWritten.toFloat() / totalBytes * 100).toInt()}%) for ID: $downloadId")

                // Open output stream: use ContentResolver for SAF URIs, FileOutputStream for regular paths
                val appendMode = response.code == 206
                Log.d(TAG, "Opening file in ${if (appendMode) "append" else "overwrite"} mode")

                val outputStream: OutputStream = if (safUri != null) {
                    // SAF URI: use ContentResolver to open output stream
                    // Note: SAF doesn't support append mode directly; for resume we rely on
                    // the Range header and the server returning 206 Partial Content.
                    // We open in "wa" (write-append) mode when resuming.
                    val mode = if (appendMode) "wa" else "w"
                    applicationContext.contentResolver.openOutputStream(safUri, mode)
                        ?: throw IOException("Cannot open output stream for SAF URI: $safUri")
                } else {
                    FileOutputStream(file!!, appendMode)
                }

                outputStream.buffered().use { output ->
                    body.byteStream().buffered().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var bytes = input.read(buffer)
                        
                        while (bytes >= 0) {
                            if (isStopped) {
                                Log.d(TAG, "Download cancelled during transfer for ID: $downloadId")
                                downloadDao.updateStatus(downloadId, DownloadStatus.CANCELLED, "Download cancelled")
                                if (file != null && file.exists()) {
                                    file.delete()
                                    Log.d(TAG, "Deleted partial download file: ${file.absolutePath}")
                                }
                                return@withContext Result.failure()
                            }

                            val currentDownload = downloadDao.getDownload(downloadId)
                            if (currentDownload?.status == DownloadStatus.PAUSED) {
                                Log.d(TAG, "Download paused during transfer for ID: $downloadId")
                                return@withContext Result.retry()
                            }

                            output.write(buffer, 0, bytes)
                            bytesWritten += bytes

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgressUpdate >= progressInterval) {
                                val progress = workDataOf(
                                    KEY_PROGRESS to bytesWritten,
                                    KEY_TOTAL to totalBytes
                                )
                                Log.d(TAG, "Progress: $bytesWritten/$totalBytes bytes for ID: $downloadId")
                                setProgress(progress)
                                downloadDao.updateProgress(downloadId, bytesWritten, totalBytes, DownloadStatus.RUNNING)
                                lastProgressUpdate = currentTime
                            }
                            
                            bytes = input.read(buffer)
                        }
                    }
                }

                Log.d(TAG, "Download completed successfully for ID: $downloadId")
                downloadDao.updateProgress(downloadId, bytesWritten, totalBytes, DownloadStatus.COMPLETED)
                return@withContext Result.success()
            }

            Log.e(TAG, "No response body for ID: $downloadId")
            return@withContext Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            downloadId?.let {
                Log.e(TAG, "Updating status to FAILED for ID: $it")
                downloadDao.updateStatus(it, DownloadStatus.FAILED, e.message)
            }
            return@withContext Result.failure()
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_PROGRESS_INTERVAL = "progress_interval"
        const val DEFAULT_PROGRESS_INTERVAL = 1000L // 1 second default

        fun createWorkRequest(downloadId: String, progressInterval: Long = DEFAULT_PROGRESS_INTERVAL): OneTimeWorkRequest {
            Log.d(TAG, "Creating work request for download ID: $downloadId with progress interval: $progressInterval ms")
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(
                    KEY_DOWNLOAD_ID to downloadId,
                    KEY_PROGRESS_INTERVAL to progressInterval
                ))
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
        }
    }
}

class ProgressInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalResponse = chain.proceed(chain.request())
        val originalBody = originalResponse.body
        
        return originalResponse.newBuilder()
            .body(if (originalBody != null) ProgressResponseBody(originalBody) else null)
            .build()
    }
} 