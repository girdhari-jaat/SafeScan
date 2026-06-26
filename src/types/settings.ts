export interface AppSettings {
  showGrid: boolean;
  clickSound: boolean;
  autoOrientation: boolean;
  autoCrop: boolean;
  shadowRemoveEnabled: boolean;
  doubleFocusEnabled: boolean;
  usePhoneCamera: boolean;
  batterySaverEnabled: boolean;
  batchScan: boolean;
  autoRotation: boolean;
  autoDetectEnabled: boolean;
  flashMode: 'off' | 'auto' | 'torch';
  hdMode: 'Fast' | 'Standard' | 'High';
  isSettingsOpen: boolean;
  scannerSubTab: 'paper' | 'idcard' | 'grid';
  defaultScanFilter: 'original' | 'bnw' | 'magic' | 'grayscale';
  hasSeededTutorial: boolean;
  // Dynamic Branding and URLs (Rebranding Audit)
  brandColor: 'emerald' | 'indigo' | 'violet' | 'amber' | 'crimson';
  customAppName: string;
  customContactUrl: string;
  customBackupUrl: string;
  uiLanguage: 'en' | 'ur' | 'es' | 'ar' | 'hi' | 'sd';
  offlineMode: boolean;
}
