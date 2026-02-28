import type {TurboModule} from 'react-native';
import {Platform, TurboModuleRegistry} from 'react-native';

export interface DownloadConfig {
  destination: string;
  authToken?: string;
  networkType?: 'WIFI' | 'ANY';
  progressInterval?: number;
  priority?: number;
}

export interface DownloadResponse {
  downloadId: string;
}

export interface ActiveDownload {
  id: string;
  url: string;
  destination: string;
  progress: number;
  status: string;
}

export interface Spec extends TurboModule {
  // Event emitter methods
  addListener(eventName: string): void;
  removeListeners(count: number): void;

  // Download operations
  startDownload(url: string, config: DownloadConfig): Promise<DownloadResponse>;
  pauseDownload(downloadId: string): Promise<boolean>;
  resumeDownload(downloadId: string): Promise<boolean>;
  retryDownload(downloadId: string): Promise<boolean>;
  cancelDownload(downloadId: string): Promise<boolean>;

  // Query operations
  getActiveDownloads(): Promise<ActiveDownload[]>;
  reattachDownloadObserver(downloadId: string): Promise<boolean>;

  // Debug operations
  logDownloadDatabase(): Promise<boolean>;

  // Storage Access Framework (SAF) operations for Android 10+
  // Takes persistent read/write URI permissions for a directory tree URI
  // obtained via ACTION_OPEN_DOCUMENT_TREE (pickDirectory).
  takePersistableUriPermission(uri: string): Promise<boolean>;

  // Creates a file (and any needed subdirectories) within a SAF tree URI.
  // treeUri: the content:// tree URI granted by ACTION_OPEN_DOCUMENT_TREE
  // relativePath: path relative to the tree root, e.g. "models/hf/author/repo/model.gguf"
  // Returns the content:// URI of the created (or existing) file.
  createSafFile(treeUri: string, relativePath: string): Promise<string>;
}

// Only load the module on Android
export default Platform.OS === 'android'
  ? TurboModuleRegistry.getEnforcing<Spec>('DownloadModule')
  : (null as any as Spec);
