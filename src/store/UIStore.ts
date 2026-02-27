import {Appearance, Platform} from 'react-native';

import {makePersistable} from 'mobx-persist-store';
import {makeAutoObservable, runInAction} from 'mobx';
import AsyncStorage from '@react-native-async-storage/async-storage';
import * as RNFS from '@dr.pogodin/react-native-fs';
import {
  l10n,
  supportedLanguages as localesSupportedLanguages,
  type AvailableLanguage,
} from '../locales';
import {ErrorState} from '../utils/errors';

export class UIStore {
  static readonly GROUP_KEYS = {
    READY_TO_USE: 'ready_to_use',
    AVAILABLE_TO_DOWNLOAD: 'available_to_download',
  } as const;

  pageStates = {
    modelsScreen: {
      filters: [] as string[],
      expandedGroups: {
        [UIStore.GROUP_KEYS.READY_TO_USE]: true,
      },
    },
  };

  // This is a flag to auto-navigate to the chat page after loading a model
  autoNavigatetoChat = true;

  //colorScheme = useColorScheme();
  colorScheme: 'light' | 'dark' =
    Appearance.getColorScheme() === 'dark' ? 'dark' : 'light';

  // Current selected language (default to English)
  _language: AvailableLanguage = 'en';

  // List of supported languages (derived from locales registry)
  get supportedLanguages(): readonly AvailableLanguage[] {
    return localesSupportedLanguages;
  }

  displayMemUsage = false;

  iOSBackgroundDownloading = true;

  /**
   * Custom directory for downloading GGUF model files (Android only).
   * When undefined, defaults to RNFS.DocumentDirectoryPath.
   * Persisted via AsyncStorage.
   */
  customModelsDir: string | undefined = undefined;

  benchmarkShareDialog = {
    shouldShow: true,
  };

  // Warning state for chat-related warnings (like multimodal warnings)
  chatWarning: ErrorState | null = null;

  showError(message: string) {
    // TODO: Implement error display logic (e.g., toast, alert, etc.)
    console.error(message);
  }

  setChatWarning(warning: ErrorState | null) {
    runInAction(() => {
      this.chatWarning = warning;
    });
  }

  clearChatWarning() {
    runInAction(() => {
      this.chatWarning = null;
    });
  }

  constructor() {
    makeAutoObservable(this);
    makePersistable(this, {
      name: 'UIStore',
      properties: [
        'pageStates',
        'colorScheme',
        'autoNavigatetoChat',
        'displayMemUsage',
        'benchmarkShareDialog',
        '_language',
        'customModelsDir',
      ],
      storage: AsyncStorage,
    });

    // backwards compatibility. Removed this from the ui settings screen.
    this.iOSBackgroundDownloading = true;
  }

  setValue<T extends keyof typeof this.pageStates>(
    page: T,
    key: keyof (typeof this.pageStates)[T],
    value: any,
  ) {
    runInAction(() => {
      if (this.pageStates[page]) {
        this.pageStates[page][key] = value;
      } else {
        console.error(`Page '${page}' does not exist in pageStates`);
      }
    });
  }

  setColorScheme(colorScheme: 'light' | 'dark') {
    runInAction(() => {
      this.colorScheme = colorScheme;
    });
  }

  setLanguage(language: AvailableLanguage) {
    runInAction(() => {
      this._language = language;
    });
  }
  get language() {
    // If the language is not in l10n, return 'en'
    // This can happen when the app removes a language from l10n
    return this._language in l10n ? this._language : 'en';
  }

  get l10n() {
    return l10n[this.language];
  }

  setAutoNavigateToChat(value: boolean) {
    runInAction(() => {
      this.autoNavigatetoChat = value;
    });
  }

  setDisplayMemUsage(value: boolean) {
    runInAction(() => {
      this.displayMemUsage = value;
    });
  }

  setiOSBackgroundDownloading(value: boolean) {
    runInAction(() => {
      this.iOSBackgroundDownloading = value;
    });
  }

  setBenchmarkShareDialogPreference(shouldShow: boolean) {
    runInAction(() => {
      this.benchmarkShareDialog.shouldShow = shouldShow;
    });
  }

  /**
   * Sets a custom directory for downloading GGUF model files (Android only).
   * Pass undefined to reset to the default DocumentDirectoryPath.
   */
  setCustomModelsDir(path: string | undefined) {
    runInAction(() => {
      this.customModelsDir = path;
    });
  }

  /**
   * Returns the base directory for model storage.
   * On Android: returns customModelsDir if set, otherwise DocumentDirectoryPath.
   * On iOS: always returns DocumentDirectoryPath (custom dir not supported).
   */
  get modelsBaseDir(): string {
    if (Platform.OS === 'android' && this.customModelsDir) {
      return this.customModelsDir;
    }
    return RNFS.DocumentDirectoryPath;
  }
}

export const uiStore = new UIStore();
