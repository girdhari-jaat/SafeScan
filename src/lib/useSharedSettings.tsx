import {
  createContext,
  useContext,
  useState,
  useCallback,
  useEffect,
  useMemo,
  ReactNode,
} from "react";
import { AppSettings } from "../types/settings";

interface SettingsContextType {
  settings: AppSettings;
  updateSetting: (key: keyof AppSettings, value: any) => void;
  resetSettings: () => void;
}

const SettingsContext = createContext<SettingsContextType | undefined>(
  undefined,
);

// Safe localStorage access for restricted iframe environments
const safeGetItem = (key: string): string | null => {
  try {
    return localStorage.getItem(key);
  } catch (e) {
    console.warn(`[SafeScan] localStorage.getItem(${key}) failed:`, e);
    return null;
  }
};

const safeSetItem = (key: string, value: string) => {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    console.warn(`[SafeScan] localStorage.setItem(${key}) failed:`, e);
  }
};

const DEFAULT_SETTINGS: AppSettings = {
  showGrid: true,
  clickSound: true,
  autoOrientation: false,
  autoCrop: true,
  shadowRemoveEnabled: false,
  doubleFocusEnabled: false,
  usePhoneCamera: false,
  batterySaverEnabled: false,
  batchScan: true,
  autoRotation: false,
  autoDetectEnabled: false,
  flashMode: "off",
  hdMode: "Fast",
  isSettingsOpen: false,
  scannerSubTab: "paper",
  defaultScanFilter: "original",
  hasSeededTutorial: false,
  brandColor: "emerald",
  customAppName: "SafeScan",
  customContactUrl:
    import.meta.env.VITE_CONTACT_URL ||
    "https://wa.me/923468925992?text=Hi%20Girdhari,%20I%20have%20a%20question%20about%20SafeScan",
  customBackupUrl: import.meta.env.VITE_BACKUP_SYNC_URL || "",
  uiLanguage: "en",
  offlineMode: true,
};

const getInitialSettings = (): AppSettings => {
  const settings = { ...DEFAULT_SETTINGS };

  // Boolean settings
  const booleanKeys: (keyof AppSettings)[] = [
    "showGrid",
    "clickSound",
    "autoOrientation",
    "autoCrop",
    "shadowRemoveEnabled",
    "doubleFocusEnabled",
    "usePhoneCamera",
    "batterySaverEnabled",
    "batchScan",
    "autoRotation",
    "autoDetectEnabled",
    "isSettingsOpen",
    "hasSeededTutorial",
    "offlineMode",
  ];

  booleanKeys.forEach((key) => {
    const saved = safeGetItem(key);
    if (saved !== null) {
      (settings as any)[key] = saved === "true";
    }
  });

  // Overrides for defaults that are true
  if (safeGetItem("showGrid") === "false") settings.showGrid = false;
  if (safeGetItem("batchScan") === "false") settings.batchScan = false;

  // String & Literal settings
  settings.flashMode =
    (safeGetItem("flashMode") as any) || DEFAULT_SETTINGS.flashMode;
  settings.hdMode = (safeGetItem("hdMode") as any) || DEFAULT_SETTINGS.hdMode;
  settings.scannerSubTab =
    (safeGetItem("scannerSubTab") as any) || DEFAULT_SETTINGS.scannerSubTab;
  settings.defaultScanFilter =
    (safeGetItem("defaultScanFilter") as any) ||
    DEFAULT_SETTINGS.defaultScanFilter;
  settings.brandColor =
    (safeGetItem("brandColor") as any) || DEFAULT_SETTINGS.brandColor;
  settings.customAppName =
    safeGetItem("customAppName") || DEFAULT_SETTINGS.customAppName;
  settings.customContactUrl =
    safeGetItem("customContactUrl") || DEFAULT_SETTINGS.customContactUrl;
  settings.customBackupUrl =
    safeGetItem("customBackupUrl") || DEFAULT_SETTINGS.customBackupUrl;
  settings.uiLanguage =
    (safeGetItem("uiLanguage") as any) || DEFAULT_SETTINGS.uiLanguage;

  return settings;
};

export function SettingsProvider({ children }: { children: ReactNode }) {
  const [settings, setSettings] = useState<AppSettings>(getInitialSettings);

  const updateSetting = useCallback((key: keyof AppSettings, value: any) => {
    setSettings((prev) => {
      const resolvedValue =
        typeof value === "function" ? value(prev[key]) : value;
      const next = { ...prev, [key]: resolvedValue };

      // Save to localStorage safely
      if (resolvedValue === null || resolvedValue === undefined) {
        try {
          localStorage.removeItem(key);
        } catch (e) {}
      } else {
        safeSetItem(key, String(resolvedValue));
      }

      return next;
    });
  }, []);

  const resetSettings = useCallback(() => {
    setSettings(getInitialSettings());
  }, []);

  // Sync state across tabs
  useEffect(() => {
    const handleStorageChange = () => {
      setSettings(getInitialSettings());
    };

    window.addEventListener("storage", handleStorageChange);
    return () => window.removeEventListener("storage", handleStorageChange);
  }, []);

  const contextValue = useMemo(
    () => ({
      settings,
      updateSetting,
      resetSettings,
    }),
    [settings, updateSetting, resetSettings],
  );

  return (
    <SettingsContext.Provider value={contextValue}>
      {children}
    </SettingsContext.Provider>
  );
}

export function useSharedSettings() {
  const context = useContext(SettingsContext);
  if (context === undefined) {
    // Fallback for parts of the app not yet wrapped or where we want to keep current behavior
    // However, the goal is "Consistent Setting Access", so we should ideally wrap the whole app.
    throw new Error("useSharedSettings must be used within a SettingsProvider");
  }
  return context;
}
