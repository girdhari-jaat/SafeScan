import React, { Suspense } from "react";
import { motion } from "motion/react";
import {
  Folder,
  ShieldCheck,
  Smartphone,
  FileText,
  Settings as SettingsIcon,
  X,
  Terminal,
  Plus,
  Camera,
  RotateCw,
  BarChart2,
  Activity,
  Cpu,
  Copy,
  RefreshCw
} from "lucide-react";
import { ExportModal } from "./components/ExportModal";
import { globalRenderCountRef, globalLogsRef, showRenderStats, subscribeToLogs, clearLogs, addError, addWarning } from "./utils/renderStats";
import { useAppHook } from "./AppHook";
import { useSharedSettings } from "./lib/useSharedSettings";
import { useTranslation, Language } from "./lib/i18n";
import { useCamera } from "./contexts/CameraContext";

import DocumentGridStatic from "./components/DocumentGrid";
import EditorStatic from "./components/Editor";
import PDFReaderStatic from "./components/PDFReader";

const DocumentGrid = React.lazy(() => import("./components/DocumentGrid"));
const Editor = React.lazy(() => import("./components/Editor"));
const PDFReader = React.lazy(() => import("./components/PDFReader"));

import UnifiedScanner from "./components/UnifiedScanner";
import ViewSettings from "./components/Settings";

const ViewDocumentGrid = ({ batterySaverEnabled, ...props }: any) => {
  return batterySaverEnabled ? (
    <DocumentGrid {...props} />
  ) : (
    <DocumentGridStatic {...props} />
  );
};

const ViewPDFReader = React.forwardRef(
  ({ batterySaverEnabled, ...props }: any, ref: any) => {
    return batterySaverEnabled ? (
      <PDFReader ref={ref} {...props} />
    ) : (
      <PDFReaderStatic ref={ref} {...props} />
    );
  },
);

const ViewEditor = ({ batterySaverEnabled, ...props }: any) => {
  return batterySaverEnabled ? (
    <Editor {...props} />
  ) : (
    <EditorStatic {...props} />
  );
};

export default function App() {
  const { stopCamera } = useCamera() || { stopCamera: () => {} };
  const { settings } = useSharedSettings();
  const { t } = useTranslation(settings.uiLanguage as Language);

  const [isStatsModalOpen, setIsStatsModalOpen] = React.useState(false);
  const [logTrigger, setLogTrigger] = React.useState(0);
  const logContainerRef = React.useRef<HTMLDivElement>(null);
  const [isConfirmingExit, setIsConfirmingExit] = React.useState(false);
  const [showSplash, setShowSplash] = React.useState(true);
  const [showExitPrompt, setShowExitPrompt] = React.useState(false);
  const lastBackButtonPressRef = React.useRef(0);
  const exitTimerRef = React.useRef<any>(null);

  React.useEffect(() => {
    if (settings?.customAppName) {
      document.title = `${settings.customAppName} | Document Scanner`;
    }
  }, [settings?.customAppName]);

  React.useEffect(() => {
    const timer = setTimeout(() => setShowSplash(false), 2000);
    return () => clearTimeout(timer);
  }, []);

  React.useEffect(() => {
    const unsubscribe = subscribeToLogs(() => {
      setLogTrigger((prev) => prev + 1);
    });
    return unsubscribe;
  }, []);

  React.useEffect(() => {
    if (logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logTrigger]);

  React.useEffect(() => {
    const originalError = console.error;
    const originalWarn = console.warn;

    console.error = (...args: any[]) => {
      const msg = args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' ');
      if (
        msg.toLowerCase().includes('websocket') ||
        msg.toLowerCase().includes('failed to connect to') ||
        msg.toLowerCase().includes('websocket closed')
      ) {
        originalError.apply(console, args);
        return;
      }
      addError(msg);
      originalError.apply(console, args);
    };

    console.warn = (...args: any[]) => {
      const msg = args.map(arg => typeof arg === 'object' ? JSON.stringify(arg) : String(arg)).join(' ');
      if (
        msg.toLowerCase().includes('websocket') ||
        msg.toLowerCase().includes('failed to connect to') ||
        msg.toLowerCase().includes('websocket closed')
      ) {
        originalWarn.apply(console, args);
        return;
      }
      addWarning(msg);
      originalWarn.apply(console, args);
    };

    const handleError = (event: ErrorEvent) => {
      const msg = event.message || 'Unhandled Runtime Error';
      if (
        msg.toLowerCase().includes('websocket') ||
        msg.toLowerCase().includes('failed to connect to') ||
        msg.toLowerCase().includes('websocket closed')
      ) {
        return;
      }
      addError(msg);
    };

    const handleRejection = (event: PromiseRejectionEvent) => {
      const msg = `Async Rejection: ${event.reason || 'Unknown prompt'}`;
      if (
        msg.toLowerCase().includes('websocket') ||
        msg.toLowerCase().includes('failed to connect to') ||
        msg.toLowerCase().includes('websocket closed')
      ) {
        return;
      }
      addError(msg);
    };

    window.addEventListener('error', handleError);
    window.addEventListener('unhandledrejection', handleRejection);

    return () => {
      console.error = originalError;
      console.warn = originalWarn;
      window.removeEventListener('error', handleError);
      window.removeEventListener('unhandledrejection', handleRejection);
    };
  }, []);

  const [isBannerDismissed, setIsBannerDismissed] = React.useState(() => {
    return (
      localStorage.getItem("safe-scan-install-banner-dismissed") === "true"
    );
  });

  const [isStandalone, setIsStandalone] = React.useState(() => {
    return (
      window.matchMedia("(display-mode: standalone)").matches ||
      (navigator as any).standalone === true
    );
  });

  React.useEffect(() => {
    const mq = window.matchMedia("(display-mode: standalone)");
    const onChange = (e: MediaQueryListEvent) => {
      setIsStandalone(e.matches || (navigator as any).standalone === true);
    };
    if (mq.addEventListener) {
      mq.addEventListener("change", onChange);
    } else {
      mq.addListener(onChange);
    }
    return () => {
      if (mq.removeEventListener) {
        mq.removeEventListener("change", onChange);
      } else {
        mq.removeListener(onChange);
      }
    };
  }, []);

  const handleDismissBanner = React.useCallback(() => {
    localStorage.setItem("safe-scan-install-banner-dismissed", "true");
    setIsBannerDismissed(true);
  }, []);

  const {
    currentView,
    setCurrentView,
    scannerSubTab,
    setScannerSubTab,
    activeDocId,
    setActiveDocId,
    newlyCapturedPageId,
    setNewlyCapturedPageId,
    capturedBatchPageIds,
    setCapturedBatchPageIds,
    documents,
    setDocuments,
    pages,
    errorNotice,
    deferredPrompt,
    setDeferredPrompt,
    isInstallModalOpen,
    setIsInstallModalOpen,
    toastMessage,
    exportModal,
    setExportModal,
    fileInputRef,
    pdfReaderRef,
    cleanupEmptyDocuments,
    handleCreateDocument,
    handleDeleteDocument,
    handleDeleteDocuments,
    handleRenameDocument,
    handleUpdateDocumentTags,
    handleDeletePage,
    handleUpdatePage,
    handleReorderPages,
    handleAddRawImagePage,
    handleRetakePageInApp,
    handleAndroidBackButton,
    handlePDFPageImport,
    handleTriggerFileInput,
    handleFileChange,
    handlePDFExportRequest,
    handleExportConfirmed,
    activeDoc,
    triggerToast,
    hasUnsavedChanges,
  } = useAppHook();

  const handleExitAttempt = React.useCallback(async () => {
    const now = Date.now();
    if (now - lastBackButtonPressRef.current < 2000) {
      const isCapacitor = typeof window !== 'undefined' && (window as any).Capacitor;
      if (isCapacitor) {
        try {
          const { App: CapApp } = await import('@capacitor/app');
          CapApp.exitApp();
        } catch (err) {
          console.error(err);
        }
      } else {
        triggerToast("Exiting...");
      }
      setShowExitPrompt(false);
    } else {
      lastBackButtonPressRef.current = now;
      setShowExitPrompt(true);
      if (exitTimerRef.current) {
        clearTimeout(exitTimerRef.current);
      }
      exitTimerRef.current = setTimeout(() => {
        setShowExitPrompt(false);
      }, 2000);
    }
  }, [triggerToast]);

  const handleGlobalBackPress = React.useCallback(() => {
    if (hasUnsavedChanges && !isConfirmingExit) {
      setIsConfirmingExit(true);
      triggerToast("Press back again to exit");
      setTimeout(() => setIsConfirmingExit(false), 3000);
      return false;
    }
    return true;
  }, [hasUnsavedChanges, isConfirmingExit, triggerToast]);

  const handleGlobalBack = React.useCallback(() => {
    if (handleGlobalBackPress()) {
      if (currentView === "home" || currentView === "library") {
        handleExitAttempt();
      } else {
        handleAndroidBackButton();
      }
    }
  }, [handleGlobalBackPress, currentView, handleExitAttempt, handleAndroidBackButton]);

  React.useEffect(() => {
    let listenerPromise: Promise<any> | null = null;

    const setupBackButton = async () => {
      const isCapacitor = typeof window !== 'undefined' && (window as any).Capacitor;
      if (!isCapacitor) return;

      try {
        const { App: CapApp } = await import('@capacitor/app');
        
        listenerPromise = CapApp.addListener('backButton', () => {
          if (currentView === 'home' || currentView === 'library') {
            handleExitAttempt();
          } else {
            handleGlobalBack();
          }
        });
      } catch (err) {
        console.error('Failed to set up back button listener:', err);
      }
    };

    setupBackButton();

    return () => {
      if (listenerPromise) {
        listenerPromise.then(l => l.remove()).catch(err => console.error(err));
      }
    };
  }, [currentView, handleGlobalBack, handleExitAttempt]);

  const handleSelectDocument = React.useCallback(
    (id: string) => {
      setActiveDocId(id);
      setCurrentView("editor");
    },
    [setActiveDocId, setCurrentView],
  );

  const handleAddScanToDocument = React.useCallback(
    (id: string) => {
      setActiveDocId(id);
      setCurrentView("camera");
    },
    [setActiveDocId, setCurrentView],
  );

  const handleScannerCapture = React.useCallback(
    (blob: Blob, isBatch: boolean, corners: any, forceCrop?: boolean, needsDetection?: boolean) => {
      handleAddRawImagePage(blob, isBatch, corners, undefined, forceCrop, undefined, needsDetection);
    },
    [handleAddRawImagePage],
  );

  const handleScannerDone = React.useCallback(() => {
    if (capturedBatchPageIds.length > 0) {
      setNewlyCapturedPageId(capturedBatchPageIds[0]);
    } else {
      setNewlyCapturedPageId(null);
    }
    setCurrentView("editor");
  }, [setCurrentView, capturedBatchPageIds, setNewlyCapturedPageId]);

  const handleScannerClose = React.useCallback(() => {
    setCapturedBatchPageIds([]);
    setNewlyCapturedPageId(null);
    setCurrentView("home");
  }, [setCurrentView, setCapturedBatchPageIds, setNewlyCapturedPageId]);

  const handleHomeCapture = React.useCallback(
    (blob: Blob, isBatch: boolean, corners: any, forceCrop?: boolean, needsDetection?: boolean) => {
      let docId = activeDocId;
      if (!docId) {
        docId = `doc_${crypto.randomUUID()}`;
        const defaultTitle = `Document ${new Date().toLocaleDateString()} ${new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: false })}`;
        const newDoc = {
          id: docId,
          title: defaultTitle,
          createdAt: Date.now(),
          updatedAt: Date.now(),
          pageIds: [],
          tags: ["Scan"],
        };
        setDocuments([newDoc, ...documents]);
        setActiveDocId(docId);
      }
      handleAddRawImagePage(blob, isBatch, corners, docId, forceCrop, undefined, needsDetection);
    },
    [
      activeDocId,
      documents,
      setDocuments,
      setActiveDocId,
      handleAddRawImagePage,
    ],
  );

  const handleHomeCaptureDone = React.useCallback(() => {
    if (capturedBatchPageIds.length > 0) {
      setNewlyCapturedPageId(capturedBatchPageIds[0]);
    } else {
      setNewlyCapturedPageId(null);
    }
    setCurrentView("editor");
  }, [setCurrentView, capturedBatchPageIds, setNewlyCapturedPageId]);

  const handleClosePDF = React.useCallback(() => {
    pdfReaderRef.current?.triggerReset?.();
    setCurrentView("library");
  }, [pdfReaderRef, setCurrentView]);

  const handleEditorBack = React.useCallback(() => {
    setActiveDocId(null);
    setCurrentView("library");
    cleanupEmptyDocuments();
  }, [setActiveDocId, setCurrentView, cleanupEmptyDocuments]);

  const handleEditorAddPage = React.useCallback(() => {
    setCurrentView("camera");
  }, [setCurrentView]);

  const handleClearInitialCropping = React.useCallback(() => {
    setNewlyCapturedPageId(null);
  }, [setNewlyCapturedPageId]);

  const handleTabHome = React.useCallback(() => {
    if (currentView === "home" || currentView === "camera") {
      const event = new CustomEvent("scanner-tab-pressed");
      window.dispatchEvent(event);
    } else {
      setCurrentView("home");
      setActiveDocId(null);
      cleanupEmptyDocuments();
    }
  }, [currentView, setCurrentView, setActiveDocId, cleanupEmptyDocuments]);

  const handleTabLibrary = React.useCallback(() => {
    setCurrentView("library");
    setActiveDocId(null);
    cleanupEmptyDocuments();
  }, [setCurrentView, setActiveDocId, cleanupEmptyDocuments]);

  const handleTabPDF = React.useCallback(() => {
    setCurrentView("pdf");
    setActiveDocId(null);
    cleanupEmptyDocuments();
  }, [setCurrentView, setActiveDocId, cleanupEmptyDocuments]);

  const handleTabSettings = React.useCallback(() => {
    setCurrentView("settings");
    setActiveDocId(null);
    cleanupEmptyDocuments();
  }, [setCurrentView, setActiveDocId, cleanupEmptyDocuments]);

  React.useEffect(() => {
    try {
      const gScreen = window.screen as any;
      if (
        gScreen &&
        gScreen.orientation &&
        typeof gScreen.orientation.lock === "function"
      ) {
        gScreen.orientation.lock("portrait").catch(() => {
          // Programmatic lock might not always succeed in certain iframe/browser environments, which is normal and gracefully bypassed
        });
      }
    } catch (e) {
      // Ignore
    }
  }, []);

  React.useEffect(() => {
    return () => {
      showRenderStats();
    };
  }, []);

  React.useEffect(() => {
    const isCameraView = ["home", "camera", "card"].includes(currentView);
    const usePhoneCamera = !!settings?.usePhoneCamera;
    
    if (!isCameraView || usePhoneCamera) {
      // Use the official context stop method
      stopCamera();

      // Cleanup legacy global stream if it exists
      const globalStream = (window as any).globalCameraStream;
      if (globalStream) {
        try {
          globalStream.getTracks().forEach((track: any) => {
            try {
              track.stop();
            } catch (err) {}
          });
        } catch (err) {}
        (window as any).globalCameraStream = null;
      }
    } else {
      // If we are in camera view and phone camera is OFF, ensure it's started
      // UnifiedScanner normally handles this, but App serves as a safety check
    }
  }, [currentView, stopCamera, settings.usePhoneCamera]);

  const [isNavBarVisible, setIsNavBarVisible] = React.useState(true);
  const lastScrollYRef = React.useRef<{ [key: string]: number }>({});

  const handleScrollEvent = React.useCallback(
    (elementId: string, scrollTop: number) => {
      if (currentView === "library") {
        if (!isNavBarVisible) setIsNavBarVisible(true);
        return;
      }

      if (!documents || documents.length === 0) {
        if (!isNavBarVisible) setIsNavBarVisible(true);
        return;
      }

      if (scrollTop <= 15) {
        if (!isNavBarVisible) setIsNavBarVisible(true);
        lastScrollYRef.current[elementId] = scrollTop;
        return;
      }

      const lastScrollY = lastScrollYRef.current[elementId] || 0;
      const diff = scrollTop - lastScrollY;

      if (Math.abs(diff) > 8) {
        if (diff > 0) {
          if (isNavBarVisible) setIsNavBarVisible(false);
        } else {
          if (!isNavBarVisible) setIsNavBarVisible(true);
        }
        lastScrollYRef.current[elementId] = scrollTop;
      }
    },
    [documents, isNavBarVisible, currentView],
  );

  React.useEffect(() => {
    setIsNavBarVisible(true);
    lastScrollYRef.current = {};
  }, [currentView]);

  const getLoadingLabel = () => {
    switch (currentView) {
      case "home":
        return "Dashboard";
      case "library":
        return "Library";
      case "camera":
        return "Scanner";
      case "pdf":
        return "PDF Reader";
      case "editor":
        return "Editor";
      default:
        return "Module";
    }
  };

  const loadingText = settings.batterySaverEnabled
    ? `Loading ${getLoadingLabel()}...`
    : "Loading module...";

  return (
    <div className="min-h-[100dvh] bg-[var(--bg-primary)] text-[var(--text-primary)] flex items-center justify-center font-sans relative md:p-4 selection:bg-[var(--primary)] selection:text-white">
      {/* 🚀 Elegant App Splash Screen */}
      {showSplash && (
        <div className="fixed inset-0 z-[10000] bg-[var(--bg-primary)] flex flex-col items-center justify-center p-8 animate-out fade-out duration-500 fill-mode-forwards select-none pointer-events-auto">
          <div className="relative mb-8">
             <div className="w-24 h-24 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-[var(--primary)] border border-[var(--primary)]/20 animate-in zoom-in-50 duration-500">
               <ShieldCheck className="w-12 h-12" />
             </div>
             <div className="absolute inset-0 border border-[var(--primary)]/30 rounded-full scale-150 animate-[ping_2s_infinite] opacity-20" />
          </div>
          <div className="text-center space-y-2 animate-in slide-in-from-bottom-4 duration-700 delay-200">
            <h1 className="text-3xl font-black uppercase tracking-[0.3em] text-[var(--text-primary)]">
              {settings?.customAppName || "SafeScan"}
            </h1>
            <div className="flex items-center justify-center gap-3">
              <div className="h-px w-8 bg-[var(--border-color)]" />
              <p className="text-[10px] font-black uppercase tracking-widest text-[var(--text-secondary)] font-mono">
                SECURE DOCUMENT HUB
              </p>
              <div className="h-px w-8 bg-[var(--border-color)]" />
            </div>
          </div>

        </div>
      )}

      {/* Dynamic Theme Color Override Injector */}
      {settings?.brandColor && (
        <style>{`
          :root {
            --primary: ${
              settings.brandColor === "indigo" ? "#6366f1"
              : settings.brandColor === "violet" ? "#8b5cf6"
              : settings.brandColor === "amber" ? "#f59e0b"
              : settings.brandColor === "crimson" ? "#e11d48"
              : "#10b981"
            };
            --primary-hover: ${
              settings.brandColor === "indigo" ? "#4f46e5"
              : settings.brandColor === "violet" ? "#7c3aed"
              : settings.brandColor === "amber" ? "#d97706"
              : settings.brandColor === "crimson" ? "#be123c"
              : "#059669"
            };
            --primary-bracket: ${
              settings.brandColor === "indigo" ? "#a5b4fc"
              : settings.brandColor === "violet" ? "#c4b5fd"
              : settings.brandColor === "amber" ? "#fcd34d"
              : settings.brandColor === "crimson" ? "#fda4af"
              : "#6ee7b7"
            };
            --primary-faint: ${
              settings.brandColor === "indigo" ? "rgba(99, 102, 241, 0.12)"
              : settings.brandColor === "violet" ? "rgba(139, 92, 246, 0.12)"
              : settings.brandColor === "amber" ? "rgba(245, 158, 11, 0.12)"
              : settings.brandColor === "crimson" ? "rgba(225, 29, 72, 0.12)"
              : "rgba(16, 185, 129, 0.12)"
            };
          }
        `}</style>
      )}

      {/* Elegant Portrait Orientation Enforcer Overlay */}
      <div className="phone-rotation-lock-overlay fixed inset-0 z-[9999] bg-[var(--bg-primary)] text-[var(--text-primary)] flex flex-col items-center justify-center p-6 text-center select-none font-sans">
        <div className="w-24 h-24 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-[var(--primary)] mb-6 border border-[var(--primary)]/20 shadow-inner">
          <RotateCw className="w-10 h-10 animate-device-rotate" />
        </div>
        <h2 className="text-xl font-bold tracking-tight uppercase mb-2">
          Portrait Locked
        </h2>
        <p className="text-xs text-[var(--text-secondary)] max-w-xs leading-relaxed">
          Please rotate your device to vertical orientation. {settings?.customAppName || "SafeScan"} works
          exclusively in portrait mode on mobile screens for precise camera
          alignment.
        </p>
      </div>

      {/* Visual Ambient Background glows on larger screens */}
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_top_right,color-mix(in_srgb,var(--primary)_6%,transparent),transparent_40%)] pointer-events-none hidden md:block"></div>
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_bottom_left,color-mix(in_srgb,var(--primary)_4%,transparent),transparent_45%)] pointer-events-none hidden md:block"></div>

      {/* Portrait Phone Mockup Wrapper Container (Fluid on Actual Phones and rotated mobile views, centered canvas frame on computers) */}
      <div
        className="phone-mockup-frame flex flex-col relative overflow-hidden font-sans transition-colors duration-300 bg-[var(--bg-primary)] text-[var(--text-primary)]"
        id="phone-frame-canvas"
      >
        {/* Android Material Status Bar (Only on computer monitor preview, hidden on mobile devices) */}
        <div className="status-bar-simulated justify-between items-center px-4 h-[var(--header-height)] z-40 select-none text-[11px] font-sans tracking-tight transition-colors duration-300 bg-[var(--bg-primary)] text-[var(--text-secondary)] border-b border-[var(--border-color)]">
          {/* Leftside: Clock & Status */}
          <div className="flex items-center gap-1.5 select-none">
            <span className="font-bold transition-colors text-[var(--text-primary)]">
              {new Date().toLocaleTimeString([], {
                hour: "2-digit",
                minute: "2-digit",
                hour12: false,
              })}
            </span>
            <span className="text-[8px] font-black text-[var(--primary)] bg-[var(--primary)]/10 px-1 py-[1px] rounded tracking-widest scale-95 uppercase font-mono">
              5G
            </span>
          </div>

          {/* Centered: Simulated Android Dynamic Punch-hole camera sensor */}
          <div
            className="w-3.5 h-3.5 rounded-full shadow-inner flex items-center justify-center p-0.5 border transition-colors bg-[var(--bg-card)] border-[var(--border-color)]"
            title="Selfie Camera Lens"
          >
            <div className="w-1.5 h-1.5 rounded-full bg-black dark:bg-white"></div>
          </div>

          {/* Rightside: Wi-Fi, Signal and Battery status values */}
          <div className="flex items-center gap-2 select-none">
            <span className="text-[9px] text-[var(--primary)] font-bold uppercase tracking-wider font-mono">
              Offline
            </span>

            <svg
              className="w-3.5 h-3.5 transition-colors text-[var(--text-secondary)]"
              fill="currentColor"
              viewBox="0 0 24 24"
            >
              <path d="M12 21l-12-12c3.48-3.48 8.13-5.22 12-5.22s8.52 1.74 12 5.22l-12 12z" />
            </svg>

            <svg
              className="w-3 h-3 transition-colors text-[var(--text-secondary)]"
              fill="currentColor"
              viewBox="0 0 24 24"
            >
              <path d="M2 22h20v-20h-20v20zm14-14h4v12h-4v-12zm-5 5h3v7h-3v-7zm-5 5h3v2h-3v-2z" />
            </svg>

            <div className="flex items-center gap-0.5">
              <span className="text-[9px] font-bold font-sans text-[var(--primary)]">
                98%
              </span>
              <div className="w-4.5 h-2.5 rounded-xs p-0.5 flex items-center border transition-colors border-[var(--border-color)]">
                <div className="w-[85%] h-full bg-[var(--primary)] rounded-2xs"></div>
              </div>
            </div>
          </div>
        </div>

        {/* Global Toast Alerts */}
        {toastMessage && (
          <div className="absolute top-16 left-1/2 -translate-x-1/2 z-[100] bg-emerald-500 text-zinc-100 font-bold px-4 py-2.5 rounded-2xl shadow-xl flex items-center gap-2 animate-bounce text-xs border border-emerald-400 font-sans uppercase tracking-wider select-none">
            <ShieldCheck className="w-4 h-4" />
            <span>{toastMessage}</span>
          </div>
        )}

        {/* Centered Exit Prompt Overlay */}
        {showExitPrompt && (
          <div className="absolute inset-0 flex items-center justify-center z-[1000] bg-black/10 backdrop-blur-[2px] pointer-events-none animate-in fade-in duration-200">
            <div className="bg-zinc-900/95 border border-zinc-800 text-zinc-100 font-black px-6 py-4 rounded-2xl shadow-2xl flex flex-col items-center gap-2.5 max-w-[240px] text-center pointer-events-auto">
              <ShieldCheck className="w-8 h-8 text-[var(--primary)] animate-pulse" />
              <span className="text-[11px] uppercase tracking-widest font-sans leading-snug">
                Press again to exit
              </span>
            </div>
          </div>
        )}

        {/* Dynamic Nav View Sheets router */}
        <div
          className={`flex-1 overflow-y-auto flex flex-col ${
            currentView === "camera" || currentView === "home"
              ? "p-0 gap-0 h-full"
              : currentView === "editor"
              ? "p-0 gap-0"
              : "px-4 py-5 gap-6"
          }`}
          id="app-viewport"
          onScroll={(e) =>
            handleScrollEvent("app-viewport", e.currentTarget.scrollTop)
          }
        >
          {errorNotice && (
            <div className="bg-rose-500/10 border-2 border-rose-500/20 text-rose-300 rounded-2xl p-4 text-xs font-medium leading-relaxed mb-4">
              ⚠️ {errorNotice}
            </div>
          )}

          <Suspense
            fallback={
              <div className="flex-1 flex flex-col items-center justify-center text-[var(--text-secondary)] font-mono text-sm animate-pulse h-full">
                {loadingText}
              </div>
            }
          >
            <motion.div
              key={currentView}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.15 }}
              className="h-full w-full flex flex-col"
            >
              {/* Home view block is now unified directly within the primary scanner block below */}

              {currentView === "library" && (
                <ViewDocumentGrid
                  batterySaverEnabled={settings.batterySaverEnabled}
                  documents={documents}
                  pages={pages}
                  onSelectDocument={handleSelectDocument}
                  onCreateDocument={handleCreateDocument}
                  onDeleteDocument={handleDeleteDocument}
                  onDeleteDocuments={handleDeleteDocuments}
                  onRenameDocument={handleRenameDocument}
                  onExportPDF={handlePDFExportRequest}
                  onAddScanToDocument={handleAddScanToDocument}
                  onUpdateDocumentTags={handleUpdateDocumentTags}
                  onTriggerImport={handleTriggerFileInput}
                  onTriggerScan={handleTabHome}
                />
              )}

              {(currentView === "home" || currentView === "camera") && (
                <div
                  className={`flex-1 flex flex-col w-full bg-[var(--bg-primary)] text-[var(--text-primary)] p-0 rounded-none border-0 overflow-hidden relative select-none transition-all duration-300 ${
                    isNavBarVisible
                      ? "max-h-[calc(100vh-105px-env(safe-area-inset-bottom,0px))] mb-[calc(4.75rem+env(safe-area-inset-bottom,0px))] md:max-h-[calc(100vh-190px)] md:mb-[148px]"
                      : "max-h-full mb-0"
                  }`}
                  id="unified-scanner-container"
                >
                  {/* Unified Scanner Stage */}
                  <UnifiedScanner
                    currentTab={scannerSubTab as any}
                    onChangeTab={setScannerSubTab}
                    batterySaverEnabled={settings.batterySaverEnabled}
                    onCapture={
                      currentView === "home"
                        ? handleHomeCapture
                        : handleScannerCapture
                    }
                    onFallbackUpload={handleTriggerFileInput}
                    onDone={
                      currentView === "home"
                        ? handleHomeCaptureDone
                        : handleScannerDone
                    }
                    onClose={
                      currentView === "home"
                        ? () => {
                            setCurrentView("library");
                            cleanupEmptyDocuments();
                          }
                        : handleScannerClose
                    }
                    pages={pages.filter((p) => p.docId === activeDocId)}
                    onDeletePage={handleDeletePage}
                    onRetakePage={handleRetakePageInApp}
                    documentTitle={activeDoc?.title}
                    onUpdatePage={handleUpdatePage}
                    onReorderPages={handleReorderPages}
                  />
                </div>
              )}

              {currentView === "pdf" && (
                <div className="flex-1 flex flex-col justify-start py-2 h-full gap-4">
                  <ViewPDFReader
                    batterySaverEnabled={settings.batterySaverEnabled}
                    ref={pdfReaderRef}
                    onImportPage={handlePDFPageImport}
                    onClose={handleClosePDF}
                    onScroll={(e) =>
                      handleScrollEvent("pdf-viewport", e.currentTarget.scrollTop)
                    }
                  />
                </div>
              )}

              {currentView === "editor" && activeDoc && (
                <ViewEditor
                  batterySaverEnabled={settings.batterySaverEnabled}
                  document={activeDoc}
                  pages={pages}
                  onBack={handleEditorBack}
                  onAddPage={handleEditorAddPage}
                  onImportPage={handleTriggerFileInput}
                  onUpdateDocumentTags={handleUpdateDocumentTags}
                  onDeletePage={handleDeletePage}
                  onUpdatePage={handleUpdatePage}
                  onReorderPages={handleReorderPages}
                  initialCroppingPageId={newlyCapturedPageId || undefined}
                  onClearInitialCropping={handleClearInitialCropping}
                  onRenameDocument={handleRenameDocument}
                />
              )}
              {currentView === "settings" && (
                <div className="flex-1 flex flex-col justify-start py-2 h-full gap-4">
                  <ViewSettings
                    documentsCount={documents.length}
                    onClose={() => setCurrentView("library")}
                    onCloseToDefault={() => setCurrentView("library")}
                    canInstall={!!deferredPrompt}
                    triggerToast={triggerToast}
                    onInstall={async () => {
                      if (deferredPrompt) {
                        deferredPrompt.prompt();
                        const { outcome } = await deferredPrompt.userChoice;
                        if (outcome === "accepted") {
                          setDeferredPrompt(null);
                          triggerToast("App installed successfully!");
                        }
                      }
                    }}
                  />
                </div>
              )}
            </motion.div>
          </Suspense>
        </div>

        {/* Static Bottom Navigation Bar (Non-Draggable, Fixed at bottom) */}
        {["home", "library", "pdf", "settings", "camera"].includes(currentView) && (
          <div className="absolute bottom-[calc(1rem+env(safe-area-inset-bottom,0px))] md:bottom-[48px] left-1/2 -translate-x-1/2 z-40 w-max no-print cursor-default transition-all duration-300">
            <div
              className={`flex items-center bg-[var(--bg-card)]/90 backdrop-blur-xl border border-[var(--border-color)] px-2 py-1.5 gap-1 select-none shadow-2xl rounded-full transition-all duration-300 ${
                isNavBarVisible
                  ? "opacity-100 pointer-events-auto"
                  : "opacity-0 pointer-events-none"
              }`}
            >
              {/* Library Tab */}
              <button
                type="button"
                onClick={handleTabLibrary}
                className={`relative px-5 py-2.5 flex items-center gap-2 transition-all duration-300 cursor-pointer rounded-full outline-none ${
                  currentView === "library"
                    ? "bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/25"
                    : "text-[var(--text-secondary)] hover:bg-[var(--primary-faint)]"
                }`}
              >
                <Folder
                  className={`w-4.5 h-4.5 transition-transform duration-300 ${currentView === "library" ? "scale-110" : ""}`}
                />
                {currentView === "library" && (
                  <span className="text-[10px] font-black uppercase tracking-wider animate-in slide-in-from-left-2 fade-in duration-300">
                    {t.library}
                  </span>
                )}
              </button>

              {/* Scan Tab */}
              <button
                type="button"
                onClick={handleTabHome}
                className={`relative px-5 py-2.5 flex items-center gap-2 transition-all duration-300 cursor-pointer rounded-full outline-none ${
                  currentView === "home"
                    ? "bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/25"
                    : "text-[var(--text-secondary)] hover:bg-[var(--primary-faint)]"
                }`}
              >
                <Camera
                  className={`w-4.5 h-4.5 transition-transform duration-300 ${currentView === "home" ? "scale-110" : ""}`}
                />
                {currentView === "home" && (
                  <span className="text-[10px] font-black uppercase tracking-wider animate-in slide-in-from-left-2 fade-in duration-300">
                    {t.camera}
                  </span>
                )}
              </button>

              {/* Read / Browse PDF Tab */}
              <button
                type="button"
                onClick={handleTabPDF}
                className={`relative px-5 py-2.5 flex items-center gap-2 transition-all duration-300 cursor-pointer rounded-full outline-none ${
                  currentView === "pdf"
                    ? "bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/25"
                    : "text-[var(--text-secondary)] hover:bg-[var(--primary-faint)]"
                }`}
              >
                <FileText
                  className={`w-4.5 h-4.5 transition-transform duration-300 ${currentView === "pdf" ? "scale-110" : ""}`}
                />
                {currentView === "pdf" && (
                  <span className="text-[10px] font-black uppercase tracking-wider animate-in slide-in-from-left-2 fade-in duration-300">
                    {t.browse}
                  </span>
                )}
              </button>

              {/* Settings Tab */}
              <button
                type="button"
                onClick={handleTabSettings}
                className={`relative px-5 py-2.5 flex items-center gap-2 transition-all duration-300 cursor-pointer rounded-full outline-none ${
                    currentView === "settings"
                    ? "bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/25"
                    : "text-[var(--text-secondary)] hover:bg-[var(--primary-faint)]"
                }`}
              >
                <SettingsIcon
                  className={`w-4.5 h-4.5 transition-transform duration-300 ${currentView === "settings" ? "scale-110" : ""}`}
                />
                {currentView === "settings" && (
                  <span className="text-[10px] font-black uppercase tracking-wider animate-in slide-in-from-left-2 fade-in duration-300">
                    {t.settings}
                  </span>
                )}
              </button>
            </div>
          </div>
        )}

        {/* Interactive Virtual Android Navigation Bar (Only on computer dashboard preview, matches Android 3-button system layout) */}
        <div className={`navigation-bar-simulated justify-around items-center px-4 h-[var(--footer-height)] z-40 select-none shrink-0 transition-colors duration-300 bg-[var(--bg-primary)] border-t border-[var(--border-color)] ${!isNavBarVisible ? 'hidden' : 'flex'}`}>
          {/* Back Triangle */}
          <button
            type="button"
            onClick={handleGlobalBack}
            className="p-3 min-w-[48px] min-h-[48px] px-6 flex items-center justify-center rounded-xl transition-all active:scale-90 cursor-pointer hover:bg-[var(--primary-faint)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            title="Android System Back Button"
            id="android-back-btn"
          >
            <svg
              className="w-5 h-5 fill-current rotate-[210deg] md:-rotate-90 transition-colors text-[var(--text-secondary)]"
              viewBox="0 0 24 24"
            >
              <path d="M24 22h-24l12-20z" />
            </svg>
          </button>

          {/* Home Circle */}
          <button
            type="button"
            onClick={() => {
              setCurrentView("home");
              setActiveDocId(null);
              triggerToast("System: Returned to Home");
              cleanupEmptyDocuments();
            }}
            className="p-3 min-w-[48px] min-h-[48px] px-6 flex items-center justify-center rounded-xl transition-all active:scale-90 cursor-pointer hover:bg-[var(--primary-faint)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            title="Android System Home Button"
            id="android-home-btn"
          >
            <div className="w-5 h-5 rounded-full border-[2.5px] transition-all border-[var(--text-secondary)] hover:border-[var(--text-primary)]" />
          </button>

          {/* Recents Square */}
          <button
            type="button"
            onClick={() => {
              setIsInstallModalOpen(true);
              triggerToast("System: Android Options Panel");
            }}
            className="p-3 min-w-[48px] min-h-[48px] px-6 flex items-center justify-center rounded-xl transition-all active:scale-90 cursor-pointer hover:bg-[var(--primary-faint)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            title="Android System Recents / Installation panel"
            id="android-recents-btn"
          >
            <div className="w-4 h-4 border-[2.5px] transition-all rounded-sm border-[var(--text-secondary)] hover:border-[var(--text-primary)]" />
          </button>
        </div>

        {/* Micro Footer Trademark & Stats Display (zero external sync guarantee indicator) */}
        <div
          className={`bg-[var(--bg-primary)] px-4 h-7 text-center text-[var(--text-secondary)] text-[10px] uppercase font-mono tracking-widest select-none z-30 flex items-center justify-center flex-wrap leading-none gap-3`}
        >
          <div className="flex items-center gap-1.5">
            <ShieldCheck className="w-2.5 h-2.5 text-[var(--primary)]/80 pointer-events-none" />
            <span className="text-[var(--text-primary)] font-bold pointer-events-none text-[9px] tracking-wider uppercase">
              {settings?.customAppName || "SafeScan"} • by •{" "}
            </span>
            <button
              type="button"
              onClick={() =>
                window.open(
                  settings?.customContactUrl || `https://wa.me/923468925992?text=Hi%20Girdhari,%20I%20have%20a%20question%20about%20${settings?.customAppName || "SafeScan"}`,
                  "_blank",
                )
              }
              className="text-[var(--text-primary)] font-black underline hover:text-[var(--primary)] transition-colors uppercase cursor-pointer outline-none"
            >
              Girdhari_Jaat
            </button>
          </div>
          <span className="text-[var(--border-color)]">•</span>
          <button
            type="button"
            onClick={() => {
              showRenderStats();
              setIsStatsModalOpen(true);
            }}
            className="text-[var(--primary)] hover:text-emerald-400 active:scale-95 transition-all cursor-pointer font-bold uppercase tracking-widest text-[10px] flex items-center gap-1"
          >
            <BarChart2 className="w-2.5 h-2.5" />
            <span>Stats</span>
          </button>
        </div>

        {isStatsModalOpen && (
          <div
            className="fixed inset-0 bg-[var(--bg-overlay)] backdrop-blur-md z-[99999] flex items-center justify-center p-4 animate-in fade-in duration-200"
            id="render-stats-modal"
          >
            <div className="bg-[var(--bg-card)] border border-[var(--border-color)] rounded-3xl w-full max-w-md overflow-hidden shadow-2xl flex flex-col max-h-[90vh] animate-in fade-in zoom-in-95 duration-150">
              {/* Header */}
              <div className="p-4 border-b border-[var(--border-color)] flex justify-between items-center bg-[var(--bg-primary)]/40">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-xl bg-[var(--primary)]/10 flex items-center justify-center text-[var(--primary)]">
                    <BarChart2 className="w-4 h-4 animate-pulse" />
                  </div>
                  <div className="text-left">
                    <h3 className="text-xs font-bold text-[var(--text-primary)] uppercase font-sans tracking-wide">
                      Performance Monitor
                    </h3>
                    <p className="text-[9px] text-[var(--text-secondary)] font-mono uppercase">
                      Render Diagnostics & Info
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setIsStatsModalOpen(false)}
                  className="p-1.5 hover:bg-[var(--bg-primary)] rounded-lg text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors cursor-pointer"
                  title="Close statistics"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              {/* Body */}
              <div className="p-5 overflow-y-auto space-y-4 text-[var(--text-secondary)] text-xs">
                {/* Visual statistics bars */}
                <div className="bg-[var(--bg-primary)]/30 border border-[var(--border-color)] p-4 rounded-2xl space-y-3">
                  <h4 className="text-[10px] font-extrabold text-[var(--text-secondary)] uppercase tracking-widest flex items-center gap-1.5 mb-2 text-left">
                    <Activity className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                    Component Render Activity
                  </h4>
                  <div className="space-y-2.5">
                    {Object.entries(globalRenderCountRef.current).map(
                      ([component, count]) => {
                        const maxRenders = Math.max(
                          ...Object.values(globalRenderCountRef.current),
                          1,
                        );
                        const percentage = Math.min(
                          100,
                          Math.max(8, (count / maxRenders) * 100),
                        );

                        return (
                          <div key={component} className="space-y-1">
                            <div className="flex justify-between items-center text-[11px] font-medium font-mono">
                              <span className="text-[var(--text-primary)]">
                                {component}
                              </span>
                              <span className="text-[var(--primary)] font-bold">
                                {count}x
                              </span>
                            </div>
                            <div className="w-full bg-[var(--bg-primary)] h-2 rounded-full overflow-hidden border border-[var(--border-color)]">
                              <div
                                className="bg-[var(--primary)] h-full rounded-full transition-all duration-300"
                                style={{ width: `${percentage}%` }}
                              />
                            </div>
                          </div>
                        );
                      },
                    )}

                    {Object.keys(globalRenderCountRef.current).length === 0 && (
                      <div className="text-[var(--text-secondary)] text-center font-mono py-4">
                        No rendering events counted yet.
                      </div>
                    )}
                  </div>
                </div>

                {/* Worker Logs */}
                <div className="bg-[var(--bg-primary)]/30 border border-[var(--border-color)] p-4 rounded-2xl space-y-3">
                  <h4 className="text-[10px] font-extrabold text-[var(--text-secondary)] uppercase tracking-widest flex items-center gap-1.5 mb-2 text-left">
                    <Terminal className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                    Worker Logs
                  </h4>
                  <div ref={logContainerRef} className="max-h-40 overflow-y-auto space-y-1 font-mono text-[9px] bg-[var(--bg-primary)] p-2 rounded-lg">
                    {globalLogsRef.current.map((log, index) => (
                      <div key={index} className="text-[var(--text-primary)]">
                        {log}
                      </div>
                    ))}
                    {globalLogsRef.current.length === 0 && (
                      <div className="text-[var(--text-secondary)] italic">No logs</div>
                    )}
                  </div>
                </div>


              </div>

              {/* Footer actions */}
              <div className="p-4 bg-[var(--bg-primary)] border-t border-[var(--border-color)] flex gap-2.5">
                <button
                  type="button"
                  onClick={() => {
                    const statsText = Object.entries(
                      globalRenderCountRef.current,
                    )
                      .map(([comp, count]) => `${comp}: ${count}x`)
                      .join("\n");
                    navigator.clipboard.writeText(statsText);
                    alert("Statistics copied to clipboard!");
                  }}
                  className="flex-1 px-3 py-2.5 bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] border border-[var(--border-color)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] text-[11px] font-bold rounded-xl transition duration-150 active:scale-95 flex items-center justify-center gap-1.5 cursor-pointer"
                >
                  <Copy className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                  Copy Stats
                </button>
                <button
                  type="button"
                  onClick={() => {
                    Object.keys(globalRenderCountRef.current).forEach((key) => {
                      globalRenderCountRef.current[key] = 0;
                    });
                    clearLogs();
                    setIsStatsModalOpen(false);
                    setTimeout(() => setIsStatsModalOpen(true), 10);
                  }}
                  className="px-3 py-2.5 bg-[var(--bg-card)] hover:bg-[var(--bg-primary)] border border-[var(--border-color)] text-amber-600 hover:text-amber-500 text-[11px] font-bold rounded-xl transition duration-150 active:scale-95 flex items-center justify-center gap-1.5 cursor-pointer"
                >
                  <RefreshCw className="w-3.5 h-3.5" />
                  Reset
                </button>
              </div>
            </div>
          </div>
        )}

        {exportModal.isOpen && (
          <ExportModal
            isOpen={exportModal.isOpen}
            onClose={() => setExportModal({ isOpen: false, doc: null })}
            onExport={handleExportConfirmed}
            defaultTitle={exportModal.doc?.title || ""}
          />
        )}

        {isInstallModalOpen && (
          <div
            className="fixed inset-0 bg-[var(--bg-overlay)] backdrop-blur-md z-[100] flex items-center justify-center p-4 pt-[calc(1rem+env(safe-area-inset-top))] pb-[calc(1rem+env(safe-area-inset-bottom))]"
            id="android-setup-modal"
          >
            <div className="bg-[var(--bg-card)] border border-[var(--border-color)] rounded-3xl w-full max-w-lg overflow-hidden shadow-2xl flex flex-col max-h-[90vh]">
              {/* Header */}
              <div className="p-5 border-b border-[var(--border-color)] flex justify-between items-center bg-[var(--bg-primary)]/40">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-xl bg-[var(--primary)]/10 flex items-center justify-center text-[var(--primary)]">
                    <Smartphone className="w-4 h-4" />
                  </div>
                  <div>
                    <h3 className="text-sm font-bold text-[var(--text-primary)] uppercase font-sans tracking-wide">
                      App Installation Guide
                    </h3>
                    <p className="text-[10px] text-[var(--text-secondary)] font-mono">
                      STANDALONE PACKAGING OPTIONS
                    </p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => setIsInstallModalOpen(false)}
                  className="p-1.5 hover:bg-[var(--bg-primary)] rounded-lg text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-colors cursor-pointer"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              {/* Scrollable instructions panel */}
              <div className="p-6 overflow-y-auto space-y-6 text-[var(--text-secondary)] text-xs">
                {/* Method 1: Instant PWA Mobile Installation */}
                <div className="bg-[var(--bg-primary)] rounded-2xl p-5 border border-[var(--border-color)] relative overflow-hidden">
                  <div className="absolute top-0 right-0 w-24 h-24 bg-[var(--primary)]/5 rounded-full blur-2xl"></div>
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-6 h-6 rounded-lg bg-[var(--primary)]/20 text-[var(--primary)] flex items-center justify-center text-[10.5px] font-bold">
                      1
                    </div>
                    <span className="font-bold text-[var(--text-primary)] text-sm">
                      Standalone App (Zero-Install PWA)
                    </span>
                  </div>
                  <p className="text-zinc-400 mb-4 leading-relaxed font-sans font-medium">
                    Install this offline document scanner directly on your
                    Android phone as a standalone full-screen application. It
                    launches from your home screen app drawer, has its own
                    custom icon, operates completely offline, and saves battery
                    and storage!
                  </p>

                  {deferredPrompt ? (
                    <button
                      type="button"
                      onClick={async () => {
                        if (deferredPrompt) {
                          deferredPrompt.prompt();
                          const { outcome } = await deferredPrompt.userChoice;
                          if (outcome === "accepted") {
                            setDeferredPrompt(null);
                            setIsInstallModalOpen(false);
                            triggerToast("App installed successfully!");
                          }
                        }
                      }}
                      className="w-full py-2.5 bg-[var(--primary)] hover:bg-[var(--primary-hover)] text-white font-bold rounded-xl transition-all cursor-pointer text-xs flex items-center justify-center gap-1.5 shadow-md shadow-[var(--primary)]/20 active:scale-98"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      Install Standalone App Now
                    </button>
                  ) : (
                    <div className="space-y-4 border-t border-[var(--border-color)] pt-3">
                      <div>
                        <p className="text-[10px] uppercase font-mono text-[var(--text-primary)] tracking-wider font-extrabold mb-1">
                          For Android (Google Chrome):
                        </p>
                        <ul className="space-y-1 list-disc pl-4 text-[var(--text-secondary)] font-sans leading-relaxed">
                          <li>
                            Tap the browser’s three-dot menu icon{" "}
                            <strong className="text-[var(--text-primary)]">
                              ⋮
                            </strong>{" "}
                            in Chrome.
                          </li>
                          <li>
                            Select{" "}
                            <strong className="text-[var(--text-primary)]">
                              "Add to Home screen"
                            </strong>{" "}
                            (or{" "}
                            <strong className="text-[var(--text-primary)]">
                              "Install app"
                            </strong>
                            ).
                          </li>
                        </ul>
                      </div>
                      <div className="border-t border-[var(--border-color)] pt-2.5">
                        <p className="text-[10px] uppercase font-mono text-[var(--text-primary)] tracking-wider font-extrabold mb-1">
                          For iOS (Apple Safari):
                        </p>
                        <ul className="space-y-1 list-disc pl-4 text-[var(--text-secondary)] font-sans leading-relaxed">
                          <li>
                            Tap the Safari{" "}
                            <strong className="text-[var(--text-primary)]">
                              Share
                            </strong>{" "}
                            button (square icon with an up-arrow).
                          </li>
                          <li>
                            Scroll down and select{" "}
                            <strong className="text-[var(--text-primary)]">
                              "Add to Home Screen"
                            </strong>
                            .
                          </li>
                          <li>
                            Tap{" "}
                            <strong className="text-[var(--text-primary)]">
                              Add
                            </strong>{" "}
                            in the top-right corner to complete!
                          </li>
                        </ul>
                      </div>
                    </div>
                  )}
                </div>

                {/* Method 2: High fidelity Capacitor Native APK compilation */}
                <div className="bg-[var(--bg-primary)]/40 rounded-2xl p-5 border border-[var(--border-color)] relative">
                  <div className="flex items-center gap-2 mb-3">
                    <div className="w-6 h-6 rounded-lg bg-indigo-500/20 text-indigo-400 flex items-center justify-center text-[10.5px] font-bold">
                      2
                    </div>
                    <span className="font-bold text-[var(--text-primary)] text-sm">
                      Compile Native APK (Capacitor)
                    </span>
                  </div>
                  <p className="text-[var(--text-secondary)] mb-4 leading-relaxed font-sans font-medium">
                    We have pre-scaffolded and generated a complete native{" "}
                    <strong className="text-indigo-400 hover:underline">
                      Capacitor Mobile project
                    </strong>{" "}
                    inside the{" "}
                    <code className="text-indigo-400 bg-[var(--bg-primary)] px-1 py-0.5 rounded font-mono text-[11px]">
                      /android
                    </code>{" "}
                    folder of this project! You can build this workspace into a
                    standalone native{" "}
                    <strong className="text-[var(--text-primary)]">.apk</strong>{" "}
                    file using standard Android SDK tools.
                  </p>

                  <div className="space-y-3.5 bg-[var(--bg-primary)] p-4 rounded-xl border border-[var(--border-color)]">
                    <div className="flex items-center gap-1.5 border-b border-[var(--border-color)] pb-2">
                      <Terminal className="w-3.5 h-3.5 text-[var(--text-secondary)]" />
                      <span className="font-mono text-[10px] uppercase tracking-wider text-[var(--text-secondary)] font-extrabold">
                        Build Commands:
                      </span>
                    </div>
                    <ol className="space-y-3 font-mono text-[10.5px] text-[var(--text-secondary)] list-decimal pl-4 leading-relaxed font-bold">
                      <li>
                        <span className="text-[var(--text-secondary)]">
                          Compile web assets:
                        </span>
                        <div className="bg-[var(--bg-primary)] p-1.5 rounded mt-1 text-[var(--primary)]">
                          npm run build
                        </div>
                      </li>
                      <li>
                        <span className="text-[var(--text-secondary)]">
                          Integrate into the Android native folder:
                        </span>
                        <div className="bg-[var(--bg-primary)] p-1.5 rounded mt-1 text-indigo-400">
                          npx cap sync
                        </div>
                      </li>
                      <li>
                        <span className="text-[var(--text-secondary)]">
                          Compile the APK or test live:
                        </span>
                        <div className="bg-[var(--bg-primary)] p-1.5 rounded mt-1 text-[var(--text-primary)]">
                          npx cap run android
                        </div>
                        <div className="text-[10px] text-[var(--text-secondary)] mt-1 leading-normal italic font-sans font-medium">
                          Or open the{" "}
                          <code className="bg-[var(--bg-card)] text-[var(--text-secondary)] px-1 py-0.2 rounded font-mono">
                            ./android
                          </code>{" "}
                          directory in Android Studio to build/compile the
                          signed release APK!
                        </div>
                      </li>
                    </ol>
                  </div>
                </div>
              </div>

              {/* Footer */}
              <div className="p-4 border-t border-[var(--border-color)] flex justify-end bg-[var(--bg-primary)]/20">
                <button
                  type="button"
                  onClick={() => setIsInstallModalOpen(false)}
                  className="px-4 py-2 bg-[var(--bg-card)] border border-[var(--border-color)] hover:bg-[var(--bg-primary)] hover:text-[var(--text-primary)] text-[var(--text-secondary)] font-bold rounded-xl transition-all select-none cursor-pointer text-xs uppercase"
                >
                  Close Setup Guide
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Native Hidden input file uploader */}
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          multiple
          className="hidden"
          onChange={handleFileChange}
          id="native-hidden-file-upload"
        />
      </div>
    </div>
  );
}
