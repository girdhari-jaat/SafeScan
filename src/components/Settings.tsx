import React, { useState } from 'react';
import { 
  Settings as SettingsIcon, 
  Palette, 
  Activity, 
  MessageSquare, 
  ShieldCheck, 
  Trash2, 
  Zap, 
  Sun,
  Layout,
  RefreshCw,
  Check,
  Smartphone,
  Cpu,
  ZapOff,
  Languages,
  Database,
  Download,
  ExternalLink,
  ShieldAlert,
  Info,
  Grid,
  Camera,
  Scissors,
  RotateCw,
  Maximize,
  Eye,
  Sparkles,
  Layers,
  X
} from 'lucide-react';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useTranslation, Language } from '../lib/i18n';
import { clearDisplayCache } from '../utils/db';
import { globalImageCache } from '../utils/globalImageCache';

interface SettingsProps {
  onClose: () => void;
  onCloseToDefault?: () => void;
  onInstall?: () => void;
  canInstall?: boolean;
  triggerToast?: (msg: string) => void;
  documentsCount?: number;
}

const Section = React.memo(({ title, icon: Icon, children }: { title: string, icon: any, children: React.ReactNode }) => (
  <div className="bg-[var(--bg-card)] border border-[var(--border-color)] rounded-[1.5rem] overflow-hidden shadow-sm animate-in fade-in zoom-in-95 duration-200">
    <div className="px-5 py-3 border-b border-[var(--border-color)] flex items-center gap-2.5">
      <Icon className="w-4 h-4 text-[var(--primary)]" />
      <h3 className="text-[10px] font-black uppercase tracking-[0.15em] text-[var(--text-secondary)]">{title}</h3>
    </div>
    <div className="p-5 space-y-4">
      {children}
    </div>
  </div>
));

const Toggle = React.memo(({ label, description, value, onToggle, icon: Icon }: any) => (
  <div className="flex items-center justify-between gap-4 py-1.5 border-b border-[var(--border-color)]/30 last:border-0">
    <div className="flex gap-3 items-center">
      {Icon && <Icon className="w-4 h-4 text-[var(--text-secondary)] shrink-0" />}
      <div className="flex flex-col">
        <span className="text-xs font-bold text-[var(--text-primary)]">{label}</span>
        {description && <span className="text-[9px] text-[var(--text-secondary)] leading-relaxed">{description}</span>}
      </div>
    </div>
    <button
      onClick={onToggle}
      className={`w-8 h-4 rounded-full transition-colors relative shrink-0 ${value ? 'bg-[var(--primary)]' : 'bg-[var(--border-color)]'}`}
    >
      <div className={`absolute top-0.5 left-0.5 w-3 h-3 rounded-full bg-white transition-transform shadow-sm ${value ? 'translate-x-4' : 'translate-x-0'}`} />
    </button>
  </div>
));

const Settings: React.FC<SettingsProps> = ({ onClose, onCloseToDefault, onInstall, canInstall, triggerToast, documentsCount = 0 }) => {
  const { settings, updateSetting, resetSettings } = useSharedSettings();
  const { t } = useTranslation(settings.uiLanguage as Language);
  const [showPasswordInput, setShowPasswordInput] = React.useState(false);
  const [password, setPassword] = React.useState("");
  const [diagLoading, setDiagLoading] = useState(false);
  const [diagResult, setDiagResult] = useState<any>(null);
  const [benchmarkResult, setBenchmarkResult] = useState<number | null>(null);

  const brandColors = [
    { id: 'emerald', hex: '#10b981', label: 'Emerald' },
    { id: 'indigo', hex: '#6366f1', label: 'Indigo' },
    { id: 'violet', hex: '#8b5cf6', label: 'Violet' },
    { id: 'amber', hex: '#f59e0b', label: 'Amber' },
    { id: 'crimson', hex: '#e11d48', label: 'Crimson' },
  ];

  const handleRunSystemTest = async () => {
    setDiagLoading(true);
    setDiagResult(null);
    setBenchmarkResult(null);
    try {
      // 1. Server Health Check
      const healthRes = await fetch("/api/gemini/health").then(r => r.json()).catch(() => ({
        status: "offline",
        geminiConnected: false,
        testingEnvironment: "SafeScan-Local-Mode"
      }));

      // 2. Storage Estimation
      let quotaStr = "Unknown";
      if (navigator.storage && navigator.storage.estimate) {
        const est = await navigator.storage.estimate();
        const usageMB = Math.round((est.usage || 0) / (1024 * 1024));
        const totalMB = Math.round((est.quota || 0) / (1024 * 1024));
        quotaStr = `${usageMB}MB / ${totalMB}MB`;
      }

      // 3. Pixel Pipeline Benchmark
      const t0 = performance.now();
      const canvas = document.createElement("canvas");
      canvas.width = 100;
      canvas.height = 100;
      const ctx = canvas.getContext("2d");
      if (ctx) {
        for (let x = 0; x < 50; x++) {
          ctx.fillRect(10, 10, 50, 50);
          ctx.getImageData(0, 0, 100, 100);
        }
      }
      const t1 = performance.now();
      
      setBenchmarkResult(Math.round(t1 - t0));
      setDiagResult({
        ...healthRes,
        quota: quotaStr,
        timestamp: new Date().toLocaleTimeString()
      });
    } catch (e) {
      console.error("System diagnostics failed", e);
    } finally {
      setDiagLoading(false);
    }
  };

  const handleExportBackup = () => {
    const docs = localStorage.getItem('offline_scanner_documents_list') || '[]';
    const pages = localStorage.getItem('offline_scanner_pages_list') || '[]';
    const data = {
      app: settings.customAppName,
      version: '3.6.0',
      exportDate: new Date().toISOString(),
      documents: JSON.parse(docs),
      pages: JSON.parse(pages),
      settings: settings
    };
    
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${settings.customAppName}_Backup_${new Date().toISOString().split('T')[0]}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleFactoryReset = () => {
    if (confirm("Reset all settings to defaults? Documents and items will be preserved.")) {
      // Clear specific setting keys instead of localStorage.clear()
      const settingKeys = [
        'showGrid', 'clickSound', 'autoOrientation', 'autoCrop', 
        'shadowRemoveEnabled', 'doubleFocusEnabled', 'usePhoneCamera', 
        'batterySaverEnabled', 'batchScan', 'autoRotation', 
        'autoDetectEnabled', 'flashMode', 'hdMode', 'isSettingsOpen', 
        'scannerSubTab', 'defaultScanFilter', 'hasSeededTutorial', 
        'brandColor', 'customAppName', 'customContactUrl', 'customBackupUrl', 'uiLanguage'
      ];
      
      settingKeys.forEach(key => localStorage.removeItem(key));
      resetSettings();
      triggerToast('Settings reset to defaults');
    }
  };

  const handleClearDisplayCache = async () => {
    if (confirm("Clear display cache? This will regenerate page previews as needed. Original files will not be affected.")) {
      try {
        await clearDisplayCache();
        globalImageCache.clear();
        triggerToast?.('Display cache cleared successfully');
      } catch (e) {
        triggerToast?.('Failed to clear display cache');
      }
    }
  };

  const [activeTab, setActiveTab] = useState<'general' | 'appearance' | 'scanner' | 'system'>('general');



  const tabs = [
    { id: 'general', label: (t as any).tabGeneral || 'General', icon: Languages },
    { id: 'appearance', label: (t as any).tabTheme || 'Theme', icon: Palette },
    { id: 'scanner', label: (t as any).tabScanner || 'Scanner', icon: Zap },
    { id: 'system', label: (t as any).tabSystem || 'System', icon: Activity },
  ] as const;

  return (
    <div className="flex-1 flex flex-col h-full bg-[var(--bg-primary)] overflow-y-auto px-4 pt-[calc(1.25rem+env(safe-area-inset-top))] pb-[calc(100px+env(safe-area-inset-bottom))] gap-4 scrollbar-none animate-in fade-in duration-350">
      {/* Top Header Row */}
      <div className="flex items-center justify-between px-1 shrink-0">
        <div className="flex items-center gap-3">
          {onCloseToDefault && (
            <button 
              onClick={onCloseToDefault}
              className="w-10 h-10 rounded-2xl bg-[var(--bg-card)] flex items-center justify-center text-[var(--text-secondary)] border border-[var(--border-color)] hover:text-[var(--text-primary)] transition-all cursor-pointer active:scale-90"
              title="Close Settings to Home"
            >
              <X className="w-5 h-5" />
            </button>
          )}
          <button 
            onClick={onClose}
            className="w-10 h-10 rounded-2xl bg-[var(--bg-card)] flex items-center justify-center text-[var(--text-secondary)] border border-[var(--border-color)] hover:text-[var(--text-primary)] transition-all cursor-pointer active:scale-90"
          >
            <Layout className="w-5 h-5 -rotate-90" />
          </button>
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-[var(--primary)]/10 flex items-center justify-center text-[var(--primary)] border border-[var(--primary)]/20">
              <SettingsIcon className="w-4 h-4" />
            </div>
            <div>
              <h2 className="text-xs font-black uppercase tracking-widest text-[var(--text-primary)]">{t.appSettings}</h2>
            </div>
          </div>
        </div>
      </div>

      {/* Modern Compact Segmented Tabs Nav */}
      <div className="bg-[var(--bg-card)] border border-[var(--border-color)] rounded-2xl p-1 grid grid-cols-4 gap-1 shrink-0">
        {tabs.map((tab) => {
          const TabIcon = tab.icon;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex flex-col sm:flex-row items-center justify-center gap-1 sm:gap-2 py-2 px-1 rounded-xl transition-all ${
                isActive 
                  ? 'bg-[var(--primary)] text-white shadow-sm' 
                  : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-primary)]/50'
              }`}
            >
              <TabIcon className="w-4 h-4" />
              <span className="text-[9px] sm:text-xs font-bold leading-none">{tab.label}</span>
            </button>
          );
        })}
      </div>

      {/* Tab Contents */}
      <div className="flex-1 min-h-0">
        {activeTab === 'general' && (
          <Section title={(t as any).generalAndLanguages || "General & Languages"} icon={Languages}>
            <div className="space-y-4">
               <div className="space-y-3">
                <label className="text-[10px] font-black uppercase text-[var(--text-secondary)] tracking-wider">{(t as any).uiLanguageSelection || "UI Language Selection"}</label>
                <div className="grid grid-cols-2 gap-2">
                  {[
                    { id: 'en', label: 'English' },
                    { id: 'ur', label: 'اردو' },
                    { id: 'sd', label: 'سنڌي' },
                    { id: 'ar', label: 'العربية' },
                    { id: 'hi', label: 'हिन्दी' },
                    { id: 'es', label: 'Español' },
                  ].map((lang) => (
                    <button
                      key={lang.id}
                      onClick={() => updateSetting('uiLanguage', lang.id)}
                      className={`px-3 py-2 rounded-xl border text-[11px] font-bold transition-all flex items-center justify-between ${
                        settings.uiLanguage === lang.id 
                          ? 'bg-[var(--primary)] border-[var(--primary)] text-white' 
                          : 'bg-[var(--bg-primary)] border-[var(--border-color)] text-[var(--text-secondary)] hover:border-[var(--text-primary)]'
                      }`}
                    >
                      {lang.label}
                      {settings.uiLanguage === lang.id && <Check className="w-3.5 h-3.5" />}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </Section>
        )}

        {activeTab === 'appearance' && (
          <Section title={(t as any).brandingAndVisuals || "Branding & Visuals"} icon={Palette}>
            <div className="space-y-4">
              <div className="space-y-2">
                <label className="text-[10px] font-black uppercase text-[var(--text-secondary)] tracking-wider">{(t as any).applicationName || "Application Name"}</label>
                <input
                  type="text"
                  value={settings.customAppName}
                  onChange={(e) => updateSetting('customAppName', e.target.value)}
                  className="w-full bg-[var(--bg-primary)] border border-[var(--border-color)] rounded-xl px-4 py-2.5 text-xs font-bold text-[var(--text-primary)] outline-none focus:border-[var(--primary)] transition-all"
                  placeholder="Scanner Name"
                />
              </div>

              <div className="space-y-2 pt-2">
                <label className="text-[10px] font-black uppercase text-[var(--text-secondary)] tracking-wider block mb-1">{(t as any).brandThemeColor || "Brand Theme Color"}</label>
                <div className="flex flex-wrap gap-3">
                  {brandColors.map((color) => (
                    <button
                      key={color.id}
                      onClick={() => updateSetting('brandColor', color.id as any)}
                      className={`w-12 h-12 rounded-full border-2 transition-all flex items-center justify-center relative ${
                        settings.brandColor === color.id ? 'border-[var(--text-primary)] scale-110 shadow-lg' : 'border-transparent hover:scale-105'
                      }`}
                      style={{ backgroundColor: color.hex }}
                      title={color.label}
                    >
                      {settings.brandColor === color.id && <Check className="w-6 h-6 text-white drop-shadow-md" />}
                    </button>
                  ))}
                </div>
              </div>
            </div>
          </Section>
        )}

        {activeTab === 'scanner' && (
          <div className="space-y-4 animate-in fade-in zoom-in-95 duration-200">
            {/* 1. Camera Presets Section */}
            <Section title={(t as any).viewfinderOptions || "Viewfinder Options"} icon={Camera}>
              <div className="space-y-4">
                {/* Flash Mode Selector */}
                <div className="space-y-2">
                  <div className="flex items-center gap-1.5">
                    <Zap className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                    <span className="text-[10px] font-black uppercase text-[var(--text-secondary)] tracking-wider">{(t as any).defaultFlashMode || "Default Flash Mode"}</span>
                  </div>
                  <div className="grid grid-cols-3 gap-2">
                    {(['off', 'auto', 'torch'] as const).map((mode) => (
                      <button
                        key={mode}
                        onClick={() => updateSetting('flashMode', mode)}
                        className={`py-1.5 rounded-xl border text-[11px] font-bold transition-all capitalize ${
                          settings.flashMode === mode 
                            ? 'bg-[var(--primary)] border-[var(--primary)] text-white' 
                            : 'bg-[var(--bg-primary)] border-[var(--border-color)] text-[var(--text-secondary)] hover:border-[var(--text-primary)]'
                        }`}
                      >
                        {mode}
                      </button>
                    ))}
                  </div>
                </div>

                {/* HD Mode Selector */}
                <div className="space-y-2">
                  <div className="flex items-center gap-1.5">
                    <Cpu className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                    <span className="text-[10px] font-black uppercase text-[var(--text-secondary)] tracking-wider">{(t as any).captureQualityTiers || "Capture Quality Tiers"}</span>
                  </div>
                  <div className="grid grid-cols-3 gap-2">
                    {(['Fast', 'Standard', 'High'] as const).map((quality) => (
                      <button
                        key={quality}
                        onClick={() => updateSetting('hdMode', quality)}
                        className={`py-1.5 rounded-xl border text-[11px] font-bold transition-all ${
                          settings.hdMode === quality 
                            ? 'bg-[var(--primary)] border-[var(--primary)] text-white' 
                            : 'bg-[var(--bg-primary)] border-[var(--border-color)] text-[var(--text-secondary)] hover:border-[var(--text-primary)]'
                        }`}
                      >
                        {quality}
                      </button>
                    ))}
                  </div>
                </div>

                {/* Default Scan Filter Selector */}
                <div className="space-y-2">
                  <div className="flex items-center gap-1.5">
                    <Sparkles className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                    <span className="text-[10px] font-black uppercase text-[var(--text-secondary)] tracking-wider">{(t as any).defaultProcessingFilter || "Default Processing Filter"}</span>
                  </div>
                  <div className="grid grid-cols-4 gap-1">
                    {[
                      { id: 'original', label: 'Original' },
                      { id: 'bnw', label: 'B&W' },
                      { id: 'magic', label: 'Magic' },
                      { id: 'grayscale', label: 'Gray' }
                    ].map((filter) => (
                      <button
                        key={filter.id}
                        onClick={() => updateSetting('defaultScanFilter', filter.id as any)}
                        className={`py-1.5 px-0.5 rounded-xl border text-[10px] font-bold transition-all truncate ${
                          settings.defaultScanFilter === filter.id 
                            ? 'bg-[var(--primary)] border-[var(--primary)] text-white' 
                            : 'bg-[var(--bg-primary)] border-[var(--border-color)] text-[var(--text-secondary)] hover:border-[var(--text-primary)]'
                        }`}
                        title={filter.label}
                      >
                        {filter.label}
                      </button>
                    ))}
                  </div>
                </div>
              </div>
            </Section>

            {/* 2. Interactive Viewfinder Switches */}
            <Section title={(t as any).realTimeOverlays || "Real-Time Overlays"} icon={Grid}>
              <div className="space-y-2.5">
                <Toggle 
                  label={(t as any).cameraGridGuidelines || "Camera Grid Guidelines"} 
                  description={(t as any).cameraGridGuidelinesDesc || "Display real-time alignment lines on screen"} 
                  value={settings.showGrid} 
                  onToggle={() => updateSetting('showGrid', !settings.showGrid)}
                  icon={Grid}
                />
                <Toggle 
                  label={(t as any).automaticCrop || "Automatic Crop"} 
                  description={(t as any).automaticCropDesc || "Bypass manual margins and crop instantly"} 
                  value={settings.autoCrop} 
                  onToggle={() => updateSetting('autoCrop', !settings.autoCrop)}
                  icon={Scissors}
                />
                <Toggle 
                  label={(t as any).liveDetect || "Live Detect"} 
                  description={(t as any).liveDetectDesc || "Smart AI targeting of document contours"} 
                  value={settings.autoDetectEnabled} 
                  onToggle={() => updateSetting('autoDetectEnabled', !settings.autoDetectEnabled)}
                  icon={Smartphone}
                />
                <Toggle 
                  label={(t as any).continuousBatchScan || "Continuous Batch Scan"} 
                  description={(t as any).continuousBatchScanDesc || "Capture multiple document pages continuously"} 
                  value={settings.batchScan} 
                  onToggle={() => updateSetting('batchScan', !settings.batchScan)}
                  icon={Layers}
                />
              </div>
            </Section>

            {/* 3. Smart Processing & Hardware Toggles */}
            <Section title={(t as any).smartProcessingAndOptics || "Smart Processing & Optics"} icon={Activity}>
              <div className="space-y-2.5">
                <Toggle 
                  label={t.shadowRemove || "Shadow Removal"} 
                  description={(t as any).shadowRemovalDesc || "Flatten lighting shadows on scanned items"} 
                  value={settings.shadowRemoveEnabled} 
                  onToggle={() => updateSetting('shadowRemoveEnabled', !settings.shadowRemoveEnabled)}
                  icon={Sun}
                />
                <Toggle 
                  label={(t as any).postScanAutoRotation || "Post-Scan Auto Rotation"} 
                  description={(t as any).postScanAutoRotationDesc || "Orient scanned page rotation automatically"} 
                  value={settings.autoRotation} 
                  onToggle={() => updateSetting('autoRotation', !settings.autoRotation)}
                  icon={RotateCw}
                />
                <Toggle 
                  label={(t as any).autoOrientationAlign || "Auto Orientation Align"} 
                  description={(t as any).autoOrientationAlignDesc || "Align orientation fit dynamically"} 
                  value={settings.autoOrientation} 
                  onToggle={() => updateSetting('autoOrientation', !settings.autoOrientation)}
                  icon={Maximize}
                />
                <Toggle 
                  label={(t as any).doubleLensAutoFocus || "Double Lens Auto Focus"} 
                  description={(t as any).doubleLensAutoFocusDesc || "Activate rapid consecutive optical focusing loops"} 
                  value={settings.doubleFocusEnabled} 
                  onToggle={() => updateSetting('doubleFocusEnabled', !settings.doubleFocusEnabled)}
                  icon={Maximize}
                />
                <Toggle 
                  label={(t as any).osSystemCamera || "OS System Camera"} 
                  description={(t as any).osSystemCameraDesc || "Execute scan capture with default device system camera UI"} 
                  value={settings.usePhoneCamera} 
                  onToggle={() => updateSetting('usePhoneCamera', !settings.usePhoneCamera)}
                  icon={Camera}
                />
                <Toggle 
                  label={t.haptic || "Haptic & Sound Alerts"} 
                  description={(t as any).hapticAndSoundAlertsDesc || "Confirm successful actions with haptic and click responses"} 
                  value={settings.clickSound} 
                  onToggle={() => updateSetting('clickSound', !settings.clickSound)}
                  icon={Activity}
                />
                <Toggle 
                  label={t.batterySaver || "Battery Saver Mode"} 
                  description={(t as any).batterySaverDesc || "Reduce visual effects and camera frame-rates to limit CPU"} 
                  value={settings.batterySaverEnabled} 
                  onToggle={() => updateSetting('batterySaverEnabled', !settings.batterySaverEnabled)}
                  icon={settings.batterySaverEnabled ? ZapOff : Zap}
                />
              </div>
            </Section>
          </div>
        )}

        {activeTab === 'system' && (
          <div className="space-y-4">
            <Section title={(t as any).offlineAndPrivacy || "Offline & Privacy"} icon={ShieldCheck}>
              <div className="space-y-2.5">
                <Toggle 
                  label={(t as any).offlineOnlyMode || "Offline-Only Mode"} 
                  description={(t as any).offlineOnlyModeDesc || "Force app to run fully offline. Disables Gemini cloud AI and server-side model processing."} 
                  value={settings.offlineMode} 
                  onToggle={() => {
                    if (settings.offlineMode) {
                      setShowPasswordInput(true);
                    } else {
                      updateSetting('offlineMode', true);
                    }
                  }}
                  icon={ShieldAlert}
                />
                {showPasswordInput && (
                  <div className="flex items-center gap-1 p-1 bg-[var(--bg-primary)] rounded-xl border border-[var(--border-color)]">
                    <input
                      type="password"
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      placeholder="Enter password"
                      className="flex-1 min-w-0 bg-transparent border-none text-xs text-[var(--text-primary)] outline-none px-2"
                    />
                    <button
                      onClick={() => {
                        if (password === "jaat") {
                          updateSetting('offlineMode', false);
                          setShowPasswordInput(false);
                          setPassword("");
                        } else {
                          alert("Incorrect password");
                          setPassword("");
                        }
                      }}
                      className="shrink-0 px-2 py-1 bg-[var(--primary)] text-white rounded-lg text-xs font-bold"
                    >
                      Confirm
                    </button>
                    <button
                      onClick={() => {
                        setShowPasswordInput(false);
                        setPassword("");
                      }}
                      className="shrink-0 px-2 py-1 bg-[var(--border-color)] text-[var(--text-primary)] rounded-lg text-xs font-bold"
                    >
                      Cancel
                    </button>
                  </div>
                )}
              </div>
            </Section>

            <Section title={(t as any).qaSandboxDiagnostics || "QA Sandbox Diagnostics"} icon={Activity}>
              <div className="space-y-3">
                {!diagResult && !diagLoading && (
                  <button
                    onClick={handleRunSystemTest}
                    className="w-full bg-zinc-900 border border-zinc-800 text-amber-500 font-bold text-[10px] uppercase tracking-[0.2em] py-3 rounded-xl flex flex-col items-center gap-1.5 hover:bg-zinc-850 active:scale-[0.98] transition-all"
                  >
                    <Cpu className="w-4 h-4" />
                    {t.qaDiagnostics || "Launch System Health Check"}
                  </button>
                )}

                {diagLoading && (
                  <div className="py-4 flex flex-col items-center justify-center gap-2 bg-[var(--bg-card)] rounded-xl border border-[var(--border-color)] border-dashed">
                    <RefreshCw className="w-6 h-6 text-amber-500 animate-spin" />
                    <span className="text-[9px] font-mono font-bold text-amber-500 uppercase tracking-widest">{(t as any).runningBenchmarks || "Running Benchmarks..."}</span>
                  </div>
                )}

                {diagResult && (
                  <div className="space-y-3 mb-2 animate-in fade-in zoom-in-95 duration-200">
                    <div className="grid grid-cols-2 gap-2">
                      <div className="bg-[var(--bg-primary)] border border-[var(--border-color)] p-2.5 rounded-lg">
                        <span className="text-[8px] font-bold text-[var(--text-secondary)] uppercase block">Server API</span>
                        <span className="text-[10px] font-black text-[var(--primary)]">● {diagResult.status}</span>
                      </div>
                      <div className="bg-[var(--bg-primary)] border border-[var(--border-color)] p-2.5 rounded-lg">
                        <span className="text-[8px] font-bold text-[var(--text-secondary)] uppercase block">AI Module</span>
                        <span className={`text-[10px] font-black ${diagResult.geminiConnected ? 'text-[var(--primary)]' : 'text-amber-500'}`}>
                          {diagResult.geminiConnected ? 'Ready' : 'Off'}
                        </span>
                      </div>
                      <div className="bg-[var(--bg-primary)] border border-[var(--border-color)] p-2.5 rounded-lg">
                        <span className="text-[8px] font-bold text-[var(--text-secondary)] uppercase block">Sync Quota</span>
                        <span className="text-[10px] font-black text-[var(--text-primary)]">{diagResult.quota}</span>
                      </div>
                      <div className="bg-[var(--bg-primary)] border border-[var(--border-color)] p-2.5 rounded-lg">
                        <span className="text-[8px] font-bold text-[var(--text-secondary)] uppercase block">Pixel Latency</span>
                        <span className="text-[10px] font-black text-[var(--text-primary)]">{benchmarkResult}ms</span>
                      </div>
                    </div>
                    
                    <button
                      onClick={() => setDiagResult(null)}
                      className="w-full py-1.5 text-[8px] font-black uppercase text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border-color)] rounded-lg transition-all"
                    >
                      Clear Diagnostics
                    </button>
                  </div>
                )}
              </div>
            </Section>

            {/* Device Diagnostics Panel / Hardware & Agent Status */}
            <Section title={(t as any).hardwareAndAgentStatus || "Hardware & Agent Status"} icon={Cpu}>
              <div className="grid grid-cols-2 gap-3 text-[10.5px]">
                <div className="bg-[var(--bg-primary)] p-2.5 rounded-xl border border-[var(--border-color)] text-left">
                  <span className="text-[var(--text-secondary)] block text-[9px] uppercase font-mono tracking-wider">
                    {(t as any).deviceMode || "Device Mode"}
                  </span>
                  <span className="text-[var(--primary)] font-bold font-sans">
                    {window.matchMedia("(display-mode: standalone)").matches
                      ? "PWA Standalone"
                      : "Browser View"}
                  </span>
                </div>

                <div className="bg-[var(--bg-primary)] p-2.5 rounded-xl border border-[var(--border-color)] text-left">
                  <span className="text-[var(--text-secondary)] block text-[9px] uppercase font-mono tracking-wider">
                    {(t as any).networkStatus || "Network Status"}
                  </span>
                  <span className="text-[var(--primary)] font-bold font-sans">
                    {navigator.onLine ? "Online (Live)" : "Offline Shield"}
                  </span>
                </div>

                <div className="bg-[var(--bg-primary)] p-2.5 rounded-xl border border-[var(--border-color)] text-left">
                  <span className="text-[var(--text-secondary)] block text-[9px] uppercase font-mono tracking-wider">
                    {(t as any).userPlatform || "User Platform"}
                  </span>
                  <span
                    className="text-[var(--text-primary)] font-medium truncate block animate-none"
                    title={navigator.userAgent}
                  >
                    {/Android/i.test(navigator.userAgent)
                      ? "Android Mobile"
                      : /iPhone|iPad|iPod/i.test(navigator.userAgent)
                        ? "iOS App"
                        : "Desktop View"}
                  </span>
                </div>

                <div className="bg-[var(--bg-primary)] p-2.5 rounded-xl border border-[var(--border-color)] text-left">
                  <span className="text-[var(--text-secondary)] block text-[9px] uppercase font-mono tracking-wider">
                    {(t as any).scannedDocs || "Scanned Docs"}
                  </span>
                  <span className="text-[var(--text-primary)] font-medium block">
                    {documentsCount} Saved Record{documentsCount !== 1 ? "s" : ""}
                  </span>
                </div>
              </div>
            </Section>

            {/* Advanced / Factory Reset */}
            <div className="bg-[var(--bg-card)] border border-[var(--border-color)] rounded-[1.5rem] p-4 flex flex-col items-center gap-3 shadow-sm">
              <p className="text-[9px] text-zinc-500 text-center leading-relaxed max-w-[240px]">
                {settings.customAppName} uses local-first IndexedDB storage. Clearing browser cache might result in data loss unless synced.
              </p>
              <div className="flex gap-4">
                <button 
                  onClick={handleClearDisplayCache}
                  className="flex items-center gap-1.5 text-[9px] font-black uppercase text-[var(--primary)] hover:opacity-80 active:scale-95 transition-all px-3 py-1 cursor-pointer"
                >
                  <RefreshCw className="w-3.5 h-3.5" /> {(t as any).clearDisplayCache || "Clear Display Cache"}
                </button>
                <button 
                  onClick={handleFactoryReset}
                  className="flex items-center gap-1.5 text-[9px] font-black uppercase text-rose-500 hover:opacity-80 active:scale-95 transition-all px-3 py-1 cursor-pointer"
                >
                  <Trash2 className="w-3.5 h-3.5" /> {t.factoryReset || "Factory Reset App"}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
      
      <div className="pb-4" />
    </div>
  );
};

export default React.memo(Settings);
