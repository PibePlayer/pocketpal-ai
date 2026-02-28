package com.pocketpal.download

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Observer
import androidx.work.*
import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.pocketpal.specs.NativeDownloadModuleSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.IOException
import java.util.*
import androidx.concurrent.futures.await

@ReactModule(name = NativeDownloadModuleSpec.NAME)
class DownloadModule(reactContext: ReactApplicationContext) : NativeDownloadModuleSpec(reactContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val downloadDao = DownloadDatabase.getInstance(reactContext).downloadDao()
    private val workManager = WorkManager.getInstance(reactContext)
    // Map to store work observers by download ID
    private val workObservers = mutableMapOf<String, Observer<List<WorkInfo>>>()

    init {
        Log.d(TAG, "Initializing DownloadModule")

        scope.launch {
            Log.d(TAG, "Logging initial download database state")
            logEntireDownloadDatabase()
        }
    }

    override fun addListener(eventName: String) {
        Log.d(TAG, "Adding listener for event: $eventName")
    }

    override fun removeListeners(count: Double) {
        Log.d(TAG, "Removing ${count.toInt()} listeners")
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        Log.d(TAG, "Sending event: $eventName with params: $params")
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private fun removeWorkObserver(downloadId: String) {
        workObservers.remove(downloadId)?.let { observer ->
            Log.d(TAG, "Removing work observer for download: $downloadId")
            val workName = getWorkName(downloadId)
            workManager.getWorkInfosForUniqueWorkLiveData(workName)
                .removeObserver(observer)
        }
    }

    override fun startDownload(url: String, config: ReadableMap, promise: Promise) {
        Log.d(TAG, "Starting download with config: $config")
        scope.launch {
            try {
                val downloadId = UUID.randomUUID().toString()
                Log.d(TAG, "Generated download ID: $downloadId")

                val destination = config.getString("destination")
                    ?: throw IllegalArgumentException("Destination path is required")
                Log.d(TAG, "Destination path: $destination")

                // Extract authorization token if provided
                val authToken = if (config.hasKey("authToken")) config.getString("authToken") else null
                if (authToken != null) {
                    Log.d(TAG, "Authorization token provided for download")
                }

                val networkType = when (config.getString("networkType")) {
                    "WIFI" -> NetworkType.WIFI
                    else -> NetworkType.ANY
                }
                Log.d(TAG, "Network type: $networkType")

                val progressInterval = config.getDouble("progressInterval")?.toLong()
                    ?: DownloadWorker.DEFAULT_PROGRESS_INTERVAL
                Log.d(TAG, "Progress interval: $progressInterval ms")

                val priority = if (config.hasKey("priority")) {
                    config.getInt("priority")
                } else {
                    0
                }
                Log.d(TAG, "Priority: $priority")

                val download = DownloadEntity(
                    id = downloadId,
                    url = url,
                    destination = destination,
                    totalBytes = 0,
                    downloadedBytes = 0,
                    status = DownloadStatus.QUEUED,
                    priority = priority,
                    networkType = networkType,
                    createdAt = System.currentTimeMillis(),
                    authToken = authToken
                )

                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Inserting download into database: $download")
                    downloadDao.insertDownload(download)
                }

                val workRequest = DownloadWorker.createWorkRequest(downloadId, progressInterval)
                Log.d(TAG, "Created work request: ${workRequest.id}")
                
                createAndRegisterObserver(downloadId)

                // Enqueue the work
                workManager.enqueueUniqueWork(
                    getWorkName(downloadId),
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    workRequest
                )

                val response = Arguments.createMap().apply {
                    putString("downloadId", downloadId)
                }
                Log.d(TAG, "Resolving promise with download ID: $downloadId")
                promise.resolve(response)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start download", e)
                promise.reject("DOWNLOAD_ERROR", e.message)
            }
        }
    }

    override fun reattachDownloadObserver(downloadId: String, promise: Promise) {
        Log.d(TAG, "Re-attaching observer for download: $downloadId")
        scope.launch {
            try {
                val download = withContext(Dispatchers.IO) {
                    downloadDao.getDownload(downloadId)
                }
                
                if (download == null) {
                    Log.w(TAG, "No download found to re-attach observer: $downloadId")
                    promise.reject("DOWNLOAD_NOT_FOUND", "Download not found")
                    return@launch
                }
                
                createAndRegisterObserver(downloadId)
                
                Log.d(TAG, "Successfully re-attached observer for download: $downloadId")
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-attach observer for download: $downloadId", e)
                promise.reject("REATTACH_ERROR", e.message)
            }
        }
    }

    override fun getActiveDownloads(promise: Promise) {
        Log.d(TAG, "Getting active downloads")
        scope.launch {
            try {
                val downloads = withContext(Dispatchers.IO) {
                    Log.d(TAG, "Fetching downloads from database")
                    downloadDao.getAllDownloads().first()
                        .filter { 
                            it.status == DownloadStatus.QUEUED || 
                            it.status == DownloadStatus.RUNNING ||
                            it.status == DownloadStatus.PAUSED 
                        }
                }
                Log.d(TAG, "Found ${downloads.size} active downloads")

                val result = Arguments.createArray()
                downloads.forEach { download ->
                    Log.d(TAG, "Processing download: ${download.id}")
                    result.pushMap(Arguments.createMap().apply {
                        putString("id", download.id)
                        putString("url", download.url)
                        putString("destination", download.destination)
                        putDouble("progress", 
                            if (download.totalBytes > 0) 
                                (download.downloadedBytes.toDouble() / download.totalBytes.toDouble()) * 100 
                            else 0.0
                        )
                        putString("status", download.status.name)
                    })
                }
                Log.d(TAG, "Resolving promise with ${downloads.size} downloads")
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get active downloads", e)
                promise.reject("FETCH_ERROR", e.message)
            }
        }
    }

    override fun logDownloadDatabase(promise: Promise) {
        Log.d(TAG, "Logging download database state (requested from JS)")
        scope.launch {
            try {
                logEntireDownloadDatabase()
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log download database", e)
                promise.reject("LOG_ERROR", e.message)
            }
        }
    }

    override fun pauseDownload(downloadId: String, promise: Promise) {
        Log.d(TAG, "Pausing download: $downloadId")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Updating status to PAUSED for: $downloadId")
                    downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED)
                }
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause download: $downloadId", e)
                promise.reject("PAUSE_ERROR", e.message)
            }
        }
    }

    override fun resumeDownload(downloadId: String, promise: Promise) {
        Log.d(TAG, "Resuming download: $downloadId")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val download = downloadDao.getDownload(downloadId)
                    if (download != null) {
                        Log.d(TAG, "Updating status to QUEUED for: $downloadId")
                        downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED)
                        
                        // Create new work request only if there isn't one running
                        val workName = getWorkName(downloadId)
                        val workInfo = workManager.getWorkInfosForUniqueWork(workName).await().firstOrNull()
                        if (workInfo == null || workInfo.state.isFinished) {
                            Log.d(TAG, "Creating new work request for: $downloadId")
                            val workRequest = DownloadWorker.createWorkRequest(downloadId)
                            workManager.enqueueUniqueWork(
                                workName,
                                androidx.work.ExistingWorkPolicy.REPLACE,
                                workRequest
                            )
                        } else {
                            Log.d(TAG, "Work is already running for: $downloadId")
                        }
                    } else {
                        Log.w(TAG, "No download found to resume: $downloadId")
                    }
                }
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume download: $downloadId", e)
                promise.reject("RESUME_ERROR", e.message)
            }
        }
    }

    override fun retryDownload(downloadId: String, promise: Promise) {
        Log.d(TAG, "Retrying download: $downloadId")
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val download = downloadDao.getDownload(downloadId)
                    if (download != null) {
                        Log.d(TAG, "Updating status to QUEUED for: $downloadId")
                        downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED)
                        Log.d(TAG, "Creating new work request for: $downloadId")
                        val workRequest = DownloadWorker.createWorkRequest(downloadId)
                        workManager.enqueue(workRequest)
                    } else {
                        Log.w(TAG, "No download found to retry: $downloadId")
                    }
                }
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to retry download: $downloadId", e)
                promise.reject("RETRY_ERROR", e.message)
            }
        }
    }

    override fun cancelDownload(downloadId: String, promise: Promise) {
        Log.d(TAG, "Cancelling download: $downloadId")
        scope.launch {
            try {
                // First update the database to mark as cancelled
                withContext(Dispatchers.IO) {
                    val download = downloadDao.getDownload(downloadId)
                    if (download != null) {
                        Log.d(TAG, "Updating status to CANCELLED for download: $downloadId")
                        downloadDao.updateStatus(downloadId, DownloadStatus.CANCELLED, "Download cancelled by user")
                        
                        // Clean up the partial download file
                        val file = File(download.destination)
                        if (file.exists()) {
                            Log.d(TAG, "Deleting partial download file: ${file.absolutePath}")
                            file.delete()
                        }
                    }
                }

                // Cancel the work using the work name format
                val workName = getWorkName(downloadId)
                val operation = workManager.cancelUniqueWork(workName)
                
                // Wait for cancellation to complete
                withContext(Dispatchers.IO) {
                    try {
                        operation.result.await()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error waiting for work cancellation", e)
                    }
                }

                // Force stop any ongoing work
                workManager.pruneWork()
                
                // Send cancellation event to notify the JS side
                sendCancellationEvent(downloadId)
                
                promise.resolve(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel download: $downloadId", e)
                promise.reject("CANCEL_ERROR", e.message)
            }
        }
    }

    private fun sendProgressEvent(downloadId: String, bytesWritten: Long, totalBytes: Long) {
        val params = Arguments.createMap().apply {
            putString("downloadId", downloadId)
            putDouble("bytesWritten", bytesWritten.toDouble())
            putDouble("totalBytes", totalBytes.toDouble())
            putDouble("progress", if (totalBytes > 0) (bytesWritten.toDouble() / totalBytes.toDouble()) * 100 else 0.0)
        }
        sendEvent("onDownloadProgress", params)
    }

    private fun sendCompletionEvent(downloadId: String, filePath: String) {
        val params = Arguments.createMap().apply {
            putString("downloadId", downloadId)
            putString("filePath", filePath)
        }
        sendEvent("onDownloadComplete", params)
    }

    private fun sendFailureEvent(downloadId: String, error: String) {
        val params = Arguments.createMap().apply {
            putString("downloadId", downloadId)
            putString("error", error)
        }
        sendEvent("onDownloadFailed", params)
    }

    private fun sendCancellationEvent(downloadId: String) {
        val params = Arguments.createMap().apply {
            putString("downloadId", downloadId)
            putString("message", "Download cancelled by user")
        }
        sendEvent("onDownloadCancelled", params)
    }

    override fun onCatalystInstanceDestroy() {
        Log.d(TAG, "Cleaning up DownloadModule")
        // Clean up all observers
        workObservers.entries.forEach { (downloadId, observer) ->
            removeWorkObserver(downloadId)
        }
        workObservers.clear()
        super.onCatalystInstanceDestroy()
        scope.cancel()
    }

    // Helper function to log the current state of a download in the database
    private suspend fun logDownloadDatabaseState(downloadId: String) {
        withContext(Dispatchers.IO) {
            try {
                val download = downloadDao.getDownload(downloadId)
                if (download != null) {
                    Log.d(TAG, "DB STATE for $downloadId: " +
                        "status=${download.status}, " +
                        "progress=${download.downloadedBytes}/${download.totalBytes} bytes " +
                        "(${if (download.totalBytes > 0) (download.downloadedBytes.toFloat() / download.totalBytes * 100).toInt() else 0}%), " +
                        "error=${download.error ?: "none"}")
                } else {
                    Log.d(TAG, "DB STATE for $downloadId: No record found in database")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log database state for $downloadId", e)
            }
        }
    }

    // Helper function to log the entire download database
    private suspend fun logEntireDownloadDatabase() {
        withContext(Dispatchers.IO) {
            try {
                val allDownloads = downloadDao.getAllDownloads().first()
                if (allDownloads.isEmpty()) {
                    Log.d(TAG, "DOWNLOAD DATABASE: Empty - No downloads found")
                    return@withContext
                }
                
                Log.d(TAG, "DOWNLOAD DATABASE: Found ${allDownloads.size} downloads")
                Log.d(TAG, "DOWNLOAD DATABASE: ----------------------------------------")
                
                allDownloads.forEachIndexed { index, download ->
                    val progressPercent = if (download.totalBytes > 0) 
                        (download.downloadedBytes.toFloat() / download.totalBytes * 100).toInt() 
                    else 0
                    
                    Log.d(TAG, "DOWNLOAD #${index + 1}:")
                    Log.d(TAG, "  ID: ${download.id}")
                    Log.d(TAG, "  URL: ${download.url}")
                    Log.d(TAG, "  Destination: ${download.destination}")
                    Log.d(TAG, "  Status: ${download.status}")
                    Log.d(TAG, "  Progress: ${download.downloadedBytes}/${download.totalBytes} bytes ($progressPercent%)")
                    Log.d(TAG, "  Priority: ${download.priority}")
                    Log.d(TAG, "  Network Type: ${download.networkType}")
                    Log.d(TAG, "  Created At: ${java.util.Date(download.createdAt)}")
                    Log.d(TAG, "  Error: ${download.error ?: "none"}")
                    Log.d(TAG, "  ----------------------------------------")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log download database", e)
            }
        }
    }

    private fun createAndRegisterObserver(downloadId: String): Observer<List<WorkInfo>> {
        Log.d(TAG, "Creating observer for download: $downloadId")
        
        // Create a new observer for this download
        val observer = Observer<List<WorkInfo>> { workInfos ->
            val workInfo = workInfos.firstOrNull() ?: return@Observer
            Log.d(TAG, "Work state changed: ${workInfo.state} for ID: $downloadId")
            
            when (workInfo.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getLong(DownloadWorker.KEY_PROGRESS, 0)
                    val total = workInfo.progress.getLong(DownloadWorker.KEY_TOTAL, 0)
                    Log.d(TAG, "Download progress: $progress/$total for ID: $downloadId")
                    sendProgressEvent(downloadId, progress, total)
                }
                WorkInfo.State.SUCCEEDED -> {
                    Log.d(TAG, "Download succeeded for ID: $downloadId")
                    scope.launch {
                        val downloadInfo = downloadDao.getDownload(downloadId)
                        if (downloadInfo != null) {
                            Log.d(TAG, "Sending completion event for ID: $downloadId")
                            sendCompletionEvent(downloadId, downloadInfo.destination)
                        } else {
                            Log.w(TAG, "Download info not found for completed download: $downloadId")
                        }
                            
                        // Log final database state after completion
                        logEntireDownloadDatabase()
                            
                        removeWorkObserver(downloadId)
                    }
                }
                WorkInfo.State.FAILED -> {
                    Log.e(TAG, "Download failed for ID: $downloadId")
                    scope.launch {
                        val downloadInfo = downloadDao.getDownload(downloadId)
                        if (downloadInfo != null) {
                            Log.e(TAG, "Error details for ID $downloadId: ${downloadInfo.error}")
                            sendFailureEvent(downloadId, downloadInfo.error ?: "Unknown error")
                        } else {
                            Log.w(TAG, "Download info not found for failed download: $downloadId")
                        }
                            
                        // Log final database state after failure
                        logEntireDownloadDatabase()
                            
                        removeWorkObserver(downloadId)
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    Log.d(TAG, "Download cancelled for ID: $downloadId")
                    
                    // Log final database state after cancellation
                    scope.launch {
                        logEntireDownloadDatabase()
                        removeWorkObserver(downloadId)
                    }
                }
                else -> {
                    Log.d(TAG, "Work state: ${workInfo.state} for ID: $downloadId")
                }
            }
        }
        
        // Remove any existing observer
        workObservers[downloadId]?.let { oldObserver ->
            Log.d(TAG, "Removing existing observer for download: $downloadId")
            val workName = getWorkName(downloadId)
            workManager.getWorkInfosForUniqueWorkLiveData(workName)
                .removeObserver(oldObserver)
        }
        
        // Register the new observer
        workObservers[downloadId] = observer
        val workName = getWorkName(downloadId)
        workManager.getWorkInfosForUniqueWorkLiveData(workName)
            .observeForever(observer)
        
        return observer
    }

    /**
     * Takes persistent read/write URI permissions for a directory tree URI
     * obtained via ACTION_OPEN_DOCUMENT_TREE (pickDirectory).
     * This allows the app to access the directory across app restarts.
     */
    override fun takePersistableUriPermission(uri: String, promise: Promise) {
        Log.d(TAG, "Taking persistable URI permission for: $uri")
        try {
            val parsedUri = Uri.parse(uri)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            reactApplicationContext.contentResolver.takePersistableUriPermission(parsedUri, flags)
            Log.d(TAG, "Successfully took persistable URI permission for: $uri")
            promise.resolve(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take persistable URI permission for: $uri", e)
            promise.reject("SAF_PERMISSION_ERROR", e.message)
        }
    }

    /**
     * Creates a file (and any needed subdirectories) within a SAF tree URI.
     * Returns the content:// URI of the created (or existing) file.
     *
     * @param treeUri The content:// tree URI granted by ACTION_OPEN_DOCUMENT_TREE
     * @param relativePath Path relative to the tree root, e.g. "models/hf/author/repo/model.gguf"
     */
    override fun createSafFile(treeUri: String, relativePath: String, promise: Promise) {
        Log.d(TAG, "Creating SAF file: treeUri=$treeUri, relativePath=$relativePath")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val parsedUri = Uri.parse(treeUri)
                    var dir = DocumentFile.fromTreeUri(reactApplicationContext, parsedUri)
                        ?: throw IllegalArgumentException("Cannot open tree URI: $treeUri")

                    // Split the relative path into directory parts and filename
                    val parts = relativePath.split("/").filter { it.isNotEmpty() }
                    if (parts.isEmpty()) {
                        throw IllegalArgumentException("relativePath is empty")
                    }

                    // Navigate/create subdirectories
                    val dirParts = parts.dropLast(1)
                    val fileName = parts.last()

                    for (part in dirParts) {
                        val existing = dir.findFile(part)
                        dir = if (existing != null && existing.isDirectory) {
                            existing
                        } else {
                            dir.createDirectory(part)
                                ?: throw IOException("Cannot create directory: $part")
                        }
                    }

                    // Create or find the file
                    val existingFile = dir.findFile(fileName)
                    val file = if (existingFile != null && existingFile.isFile) {
                        Log.d(TAG, "File already exists: ${existingFile.uri}")
                        existingFile
                    } else {
                        // Use application/octet-stream for GGUF files
                        dir.createFile("application/octet-stream", fileName)
                            ?: throw IOException("Cannot create file: $fileName")
                    }

                    file.uri.toString()
                }
                Log.d(TAG, "Created SAF file URI: $result")
                promise.resolve(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SAF file", e)
                promise.reject("SAF_CREATE_FILE_ERROR", e.message)
            }
        }
    }

    /**
     * Resolves a SAF content:// URI to a real filesystem path.
     *
     * Uses multiple strategies:
     * 1. Direct URI path parsing for ExternalStorageProvider URIs
     * 2. Query MediaStore for the DATA column
     * 3. Use /proc/self/fd/<fd> trick via ParcelFileDescriptor
     * 4. Use DocumentFile to get the absolute path
     *
     * Returns the real path, or null if it cannot be resolved.
     */
    override fun getSafFileRealPath(contentUri: String, promise: Promise) {
        Log.d(TAG, "Getting real path for SAF URI: $contentUri")
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(contentUri)
                    
                    // Strategy 1: Parse ExternalStorageProvider URI directly
                    // content://com.android.externalstorage.documents/document/primary%3ADownload%2Fmodels%2F...
                    try {
                        if (uri.authority == "com.android.externalstorage.documents") {
                            val docId = DocumentsContract.getDocumentId(uri)
                            // docId format: "primary:Download/models/hf/..." or "XXXX-XXXX:Path"
                            val colonIdx = docId.indexOf(':')
                            if (colonIdx != -1) {
                                val volume = docId.substring(0, colonIdx)
                                val relativePath = docId.substring(colonIdx + 1)
                                val basePath = if (volume == "primary") {
                                    "/storage/emulated/0"
                                } else {
                                    "/storage/$volume"
                                }
                                val fullPath = "$basePath/$relativePath"
                                // Verify the file exists
                                if (File(fullPath).exists()) {
                                    Log.d(TAG, "Resolved via ExternalStorageProvider: $fullPath")
                                    return@withContext fullPath
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "ExternalStorageProvider parsing failed: ${e.message}")
                    }
                    
                    // Strategy 2: Query MediaStore DATA column
                    try {
                        val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                        val cursor = reactApplicationContext.contentResolver.query(
                            uri, projection, null, null, null
                        )
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val dataIdx = it.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                                if (dataIdx >= 0) {
                                    val path = it.getString(dataIdx)
                                    if (!path.isNullOrEmpty() && File(path).exists()) {
                                        Log.d(TAG, "Resolved via MediaStore: $path")
                                        return@withContext path
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaStore query failed: ${e.message}")
                    }
                    
                    // Strategy 3: Use /proc/self/fd trick
                    try {
                        val pfd = reactApplicationContext.contentResolver.openFileDescriptor(uri, "r")
                        pfd?.use {
                            val fdPath = "/proc/self/fd/${it.fd}"
                            // Resolve the symlink to get the real path
                            val realPath = File(fdPath).canonicalPath
                            if (realPath != fdPath && !realPath.startsWith("/proc") && File(realPath).exists()) {
                                Log.d(TAG, "Resolved via /proc/self/fd: $realPath")
                                return@withContext realPath
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "/proc/self/fd resolution failed: ${e.message}")
                    }
                    
                    // Strategy 4: DocumentFile - try to find the file in known locations
                    try {
                        val docFile = DocumentFile.fromSingleUri(reactApplicationContext, uri)
                        if (docFile != null && docFile.exists()) {
                            // Try to construct path from display name
                            val displayName = docFile.name
                            if (!displayName.isNullOrEmpty()) {
                                // Try common external storage paths
                                val possiblePaths = listOf(
                                    "/storage/emulated/0/Download/$displayName",
                                    "/storage/emulated/0/Documents/$displayName",
                                    "/sdcard/Download/$displayName",
                                    "/sdcard/Documents/$displayName"
                                )
                                for (path in possiblePaths) {
                                    if (File(path).exists()) {
                                        Log.d(TAG, "Resolved via DocumentFile heuristic: $path")
                                        return@withContext path
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "DocumentFile resolution failed: ${e.message}")
                    }
                    
                    Log.w(TAG, "Could not resolve real path for: $contentUri")
                    null
                }
                
                if (result != null) {
                    promise.resolve(result)
                } else {
                    promise.reject("SAF_PATH_RESOLUTION_ERROR", "Cannot resolve real path for: $contentUri")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get real path for SAF URI", e)
                promise.reject("SAF_PATH_RESOLUTION_ERROR", e.message)
            }
        }
    }

    companion object {
        private const val TAG = "DownloadModule"
        private fun getWorkName(downloadId: String) = "download_$downloadId"
    }
}