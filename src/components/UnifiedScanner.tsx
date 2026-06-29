import React, { useRef, useEffect, useState } from 'react';
import { UnifiedViewfinder, UnifiedViewfinderRef } from './UnifiedViewfinder';
import { useUnifiedscannerHook } from './UnifiedscannerHook';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useCamera } from '../contexts/CameraContext';
import { CardScanner } from './CardScanner';
import { X, Crop as CropIcon, RefreshCw } from 'lucide-react';
import { getImageBlob, savePageMeta } from '../utils/db';
import Crop from './Crop';
import { PAPER_RATIOS, CARD_RATIOS } from '../constants';

interface ThumbnailImageProps {
  imageId: string;
}

const ThumbnailImage: React.FC<ThumbnailImageProps> = React.memo(({ imageId }) => {
  const [url, setUrl] = useState('');
  useEffect(() => {
    let active = true;
    let currentUrl = '';
    
    getImageBlob(imageId).then(blob => {
      if (blob && active) {
        currentUrl = URL.createObjectURL(blob);
        setUrl(currentUrl);
      }
    });

    return () => {
      active = false;
      if (currentUrl) URL.revokeObjectURL(currentUrl);
    };
  }, [imageId]);
  return url ? <img src={url} className="w-full h-full object-cover" alt="Thumbnail" /> : null;
});

interface GridViewProps {
  pages: any[];
  onDelete: (id: string) => void;
  onClose: () => void;
  onDone: () => void;
  scannerSubTab?: string;
  onUpdatePage?: (updatedPage: any) => void;
  onReorderPages?: (pageIds: string[]) => void;
}

const GridView: React.FC<GridViewProps> = React.memo(({
  pages,
  onDelete,
  onClose,
  onDone,
  scannerSubTab,
  onUpdatePage,
  onReorderPages
}) => {
  const isCardMode = scannerSubTab === 'card' || scannerSubTab === 'grid';
  const designRatio = isCardMode ? CARD_RATIOS.LANDSCAPE : PAPER_RATIOS.A4;

  const [croppingPage, setCroppingPage] = useState<any | null>(null);
  const [croppingBlob, setCroppingBlob] = useState<Blob | null>(null);
  const [isLoadingBlob, setIsLoadingBlob] = useState(false);

  // States for custom mouse/touch drag and drop reordering
  const [draggedIndex, setDraggedIndex] = useState<number | null>(null);
  const [dragOverIndex, setDragOverIndex] = useState<number | null>(null);

  const handleStartCrop = async (page: any) => {
    setIsLoadingBlob(true);
    try {
      const blob = await getImageBlob(page.originalImageId);
      if (blob) {
        setCroppingBlob(blob);
        setCroppingPage(page);
      }
    } catch (err) {
      console.error("Error loading image for crop:", err);
    } finally {
      setIsLoadingBlob(false);
    }
  };

  const saveCropData = async (page: any, corners: any, rotation: number, filter: any, adjustments: any) => {
    const pageMeta = page.meta || {
      cropPoints: page.corners,
      rotate: page.rotation,
      filter: page.filter,
      adjustments: page.adjustments
    };
    
    const newMeta = {
      ...pageMeta,
      cropPoints: corners,
      rotate: rotation,
      filter: filter,
      adjustments: {
        b: adjustments.brightness,
        c: adjustments.contrast,
        s: adjustments.saturation
      }
    };

    await savePageMeta(page.id, newMeta);
    
    if (onUpdatePage) {
      onUpdatePage({
        ...page,
        corners,
        rotation,
        filter,
        adjustments,
        meta: newMeta
      });
    }
  };

  const handleSavePageCrop = async (finalBlob: Blob, corners: any, rotation: number, filter: any, adjustments: any) => {
    if (!croppingPage) return;
    try {
      await saveCropData(croppingPage, corners, rotation, filter, adjustments);
    } catch (err) {
      console.error("Error during crop save:", err);
    } finally {
      setCroppingPage(null);
      setCroppingBlob(null);
    }
  };

  const handleSavePageCropAndNext = async (finalBlob: Blob, corners: any, rotation: number, filter: any, adjustments: any) => {
    if (!croppingPage) return;
    try {
      const currentIndex = pages.findIndex(p => p.id === croppingPage.id);
      await saveCropData(croppingPage, corners, rotation, filter, adjustments);
      
      const nextIndex = currentIndex + 1;
      if (nextIndex < pages.length) {
        const nextPage = pages[nextIndex];
        setIsLoadingBlob(true);
        const blob = await getImageBlob(nextPage.originalImageId);
        if (blob) {
          setCroppingBlob(blob);
          setCroppingPage(nextPage);
        } else {
          setCroppingPage(null);
          setCroppingBlob(null);
        }
      } else {
        setCroppingPage(null);
        setCroppingBlob(null);
      }
    } catch (err) {
      console.error("Error during save and next:", err);
      setCroppingPage(null);
      setCroppingBlob(null);
    } finally {
      setIsLoadingBlob(false);
    }
  };

  // Drag and drop mechanics - Desktop Mouse Events
  const handleDragStart = (e: React.DragEvent, index: number) => {
    setDraggedIndex(index);
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', index.toString());
  };

  const handleDragOver = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    if (draggedIndex === null || draggedIndex === index) return;
    setDragOverIndex(index);
  };

  const handleDrop = (e: React.DragEvent, index: number) => {
    e.preventDefault();
    const sourceIndex = parseInt(e.dataTransfer.getData('text/plain'), 10);
    if (isNaN(sourceIndex) || sourceIndex === index) return;
    performReorder(sourceIndex, index);
  };

  const handleDragEnd = () => {
    setDraggedIndex(null);
    setDragOverIndex(null);
  };

  // Drag and drop mechanics - Touch Events (iOS / Android compatibility)
  const handleTouchStart = (e: React.TouchEvent, index: number) => {
    setDraggedIndex(index);
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (draggedIndex === null) return;
    const touch = e.touches[0];
    const element = document.elementFromPoint(touch.clientX, touch.clientY);
    if (!element) return;
    const card = element.closest('[data-drag-page-index]');
    if (card) {
      const indexStr = card.getAttribute('data-drag-page-index');
      if (indexStr) {
        const id = parseInt(indexStr, 10);
        if (!isNaN(id) && id !== draggedIndex) {
          setDragOverIndex(id);
        }
      }
    }
  };

  const handleTouchEnd = () => {
    if (draggedIndex !== null && dragOverIndex !== null && draggedIndex !== dragOverIndex) {
      performReorder(draggedIndex, dragOverIndex);
    }
    setDraggedIndex(null);
    setDragOverIndex(null);
  };

  const performReorder = (fromIdx: number, toIdx: number) => {
    if (!onReorderPages) return;
    const newPages = [...pages];
    const [moved] = newPages.splice(fromIdx, 1);
    newPages.splice(toIdx, 0, moved);
    onReorderPages(newPages.map(p => p.id));
  };

  const croppingPageIndex = pages.findIndex(p => p.id === croppingPage?.id);
  const hasNextPage = croppingPageIndex !== -1 && croppingPageIndex < pages.length - 1;

  return (
    <div className="fixed inset-0 z-[60] bg-[var(--bg-primary)] flex flex-col animate-in fade-in duration-300">
      <div className="px-6 pt-[calc(2rem+env(safe-area-inset-top,0px))] pb-8 flex items-center justify-between border-b border-[var(--border-color)] shrink-0">
        <div className="flex flex-col">
          <h2 className="text-xl font-black text-[var(--text-primary)] uppercase tracking-tighter">Document Grid</h2>
          <p className="text-[10px] text-[var(--text-secondary)] font-bold uppercase tracking-widest">{pages.length} Pages Captured</p>
        </div>
        <button 
          onClick={onClose}
          className="w-10 h-10 rounded-full bg-[var(--bg-card)] border border-[var(--border-color)] flex items-center justify-center text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition-all cursor-pointer"
        >
          <X size={20} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-6 grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4 no-scrollbar">
        {pages.map((page, idx) => {
          const isDragging = draggedIndex === idx;
          const isDragOver = dragOverIndex === idx;
          return (
            <div 
              key={page.id}
              data-drag-page-index={idx}
              draggable="true"
              onDragStart={(e) => handleDragStart(e, idx)}
              onDragOver={(e) => handleDragOver(e, idx)}
              onDragEnd={handleDragEnd}
              onDrop={(e) => handleDrop(e, idx)}
              onTouchStart={(e) => handleTouchStart(e, idx)}
              onTouchMove={handleTouchMove}
              onTouchEnd={handleTouchEnd}
              className={`flex flex-col gap-2 group transition-all duration-200 select-none cursor-grab active:cursor-grabbing ${
                isDragging ? 'opacity-30 scale-95 border-2 border-dashed border-[var(--primary)] rounded-xl' : ''
              } ${
                isDragOver ? 'border-2 border-solid border-[var(--primary)] scale-105 rounded-xl shadow-[0_0_15px_rgba(20,184,166,0.6)]' : ''
              }`}
            >
              <div style={{ aspectRatio: designRatio }} className="relative bg-[var(--bg-card)] rounded-xl border border-[var(--border-color)] overflow-hidden shadow-2xl group-hover:border-[var(--primary)]/50 transition-all pointer-events-auto">
                 <ThumbnailImage imageId={page.originalImageId} />
                 <div className="absolute top-2 right-2 flex gap-1.5 z-30 pointer-events-auto">
                   <button 
                     onClick={(e) => {
                       e.stopPropagation();
                       handleStartCrop(page);
                     }}
                     className="w-8 h-8 rounded-full bg-cyan-600 hover:bg-cyan-500 text-white flex items-center justify-center shadow-lg cursor-pointer transition-all duration-200 active:scale-95"
                     title="Crop, rotate or apply filters"
                   >
                     <CropIcon size={13} strokeWidth={2.5} />
                   </button>
                   <button 
                     onClick={(e) => {
                       e.stopPropagation();
                       onDelete(page.id);
                     }}
                     className="w-8 h-8 rounded-full bg-rose-500 hover:bg-rose-600 text-white flex items-center justify-center shadow-lg cursor-pointer transition-all duration-200 active:scale-95"
                     title="Delete page"
                   >
                     <X size={13} strokeWidth={3} />
                   </button>
                 </div>
                 <div className="absolute bottom-2 left-2 w-6 h-6 rounded-full bg-[var(--primary)] text-white text-[10px] font-black flex items-center justify-center shadow-xl border-2 border-[var(--bg-primary)]">
                   {idx + 1}
                 </div>
              </div>
            </div>
          );
        })}
      </div>

      <div className="p-6 border-t border-[var(--border-color)] bg-[var(--bg-card)]/80 backdrop-blur-xl shrink-0">
        <button 
          onClick={onDone}
          className="w-full bg-[var(--primary)] hover:bg-[var(--primary-hover)] text-white font-black py-4 rounded-2xl flex items-center justify-center gap-2 shadow-xl shadow-[var(--primary)]/20 active:scale-95 transition-all text-xs uppercase tracking-widest cursor-pointer"
        >
          <div className="w-5 h-5 rounded-full bg-white/20 flex items-center justify-center mr-1">
            <svg className="w-3 h-3 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>
          Compile PDF Document
        </button>
      </div>

      {isLoadingBlob && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm z-[70] flex flex-col items-center justify-center p-6 text-center select-none animate-in fade-in duration-100">
          <RefreshCw className="w-8 h-8 animate-spin text-[var(--primary)] mb-3" />
          <p className="text-zinc-200 text-xs font-mono font-bold uppercase tracking-wider">
            Accessing Original Raw Image Matrix...
          </p>
        </div>
      )}

      {croppingPage && croppingBlob && (
        <div className="fixed inset-0 z-[65] bg-black">
          <Crop
            imageSrc={croppingBlob}
            initialCorners={croppingPage.corners}
            initialRotation={croppingPage.rotation}
            initialFilter={croppingPage.filter}
            initialAdjustments={croppingPage.adjustments}
            sourceType="document"
            onSave={handleSavePageCrop}
            onSaveAndNext={hasNextPage ? handleSavePageCropAndNext : undefined}
            onCancel={() => {
              setCroppingPage(null);
              setCroppingBlob(null);
            }}
          />
        </div>
      )}
    </div>
  );
});

interface UnifiedScannerProps {
  onCapture: (blob: Blob, isBatch: boolean, corners: any, forceCrop?: boolean, needsDetection?: boolean) => void;
  onClose: () => void;
  onDone: () => void;
  onFallbackUpload: () => void;
  currentTab?: 'paper' | 'idcard' | 'grid';
  onChangeTab?: (tab: 'paper' | 'idcard' | 'grid') => void;
  batterySaverEnabled?: boolean;
  pages?: any[];
  onDeletePage?: (id: string) => void;
  onRetakePage?: (id: string, blob: Blob) => void;
  documentTitle?: string;
  onUpdatePage?: (updatedPage: any) => void;
  onReorderPages?: (pageIds: string[]) => void;
}

const UnifiedScanner: React.FC<UnifiedScannerProps> = ({
  onCapture,
  onClose,
  onDone,
  onFallbackUpload,
  currentTab = 'paper',
  onChangeTab,
  pages = [],
  onDeletePage,
  onRetakePage: _onRetakePage,
  documentTitle: _documentTitle,
  onUpdatePage,
  onReorderPages
}) => {
  const { settings, updateSetting } = useSharedSettings();
  const { startCamera, stopCamera } = useCamera();
  
  const viewfinderRef = useRef<UnifiedViewfinderRef>(null);
  const [isGridViewVisible, setIsGridViewVisible] = useState(false);

  // Initialize camera on mount and cleanup on unmount - ONLY FOR PAPER MODE
  useEffect(() => {
    if (currentTab !== 'paper') {
      return;
    }
    const usePhoneCamera = !!settings?.usePhoneCamera;
    
    if (usePhoneCamera || isGridViewVisible) {
      stopCamera();
    } else {
      startCamera();
    }
  }, [startCamera, stopCamera, settings.usePhoneCamera, isGridViewVisible, currentTab]);

  // Use the unified hook for core state machine logic
  const {
    mode,
    changeMode,
    isCapturing,
    handleCapture,
    activeSlotLabel,
    idStep,
    phoneCameraInputRef,
    handlePhoneCameraFileChange,
  } = useUnifiedscannerHook({
    onCapture: (blob, autoCropped) => {
       const needsDetection = Boolean(settings.autoDetectEnabled || settings.autoCrop || settings.showGrid);
       onCapture(blob, settings.batchScan, null, autoCropped ? false : undefined, needsDetection);
       if (!settings.batchScan) {
         onDone();
       }
    },
    onIdCardCapture: (front, back) => {
       const needsDetection = Boolean(settings.autoDetectEnabled || settings.autoCrop || settings.showGrid);
       onCapture(front, true, null, true, needsDetection);
       onCapture(back, true, null, true, needsDetection);
       onDone();
    },
    settings,
    initialMode: currentTab,
    viewfinderRef
  });

  // Custom Guide Text Logic
  let displaySlotLabel = activeSlotLabel;
  if (mode === 'grid') {
    displaySlotLabel = pages.length % 2 === 0 ? "Front" : "Back";
  } else if (mode === 'idcard') {
    const cardNum = Math.floor(pages.length / 2) + 1;
    displaySlotLabel = idStep === 'front' ? `Front ${cardNum}` : `Back ${cardNum}`;
  } else if (mode === 'paper') {
    displaySlotLabel = "Document";
  }

  // Keep internal mode in sync with parent tab selection
  useEffect(() => {
    if (currentTab !== mode) {
      changeMode(currentTab);
    }
  }, [currentTab, mode, changeMode]);

  const handleTabChange = (newTab: 'paper' | 'idcard' | 'grid') => {
    changeMode(newTab);
    if (onChangeTab) {
      onChangeTab(newTab);
    }
  };

  const onCaptureClick = () => {
    handleCapture(viewfinderRef);
  };

  const onBatchToggle = () => {
    if (pages.length > 0) {
      setIsGridViewVisible(true);
    } else {
      updateSetting('batchScan', !settings.batchScan);
    }
  };

  const handleUpdateSetting = (key: string, value: any) => {
    updateSetting(key as any, value);
  };

  const handleToggleFlash = (mode: 'off' | 'auto' | 'torch') => {
    updateSetting('flashMode', mode);
  };

  const handleUpdateResolution = (mode: 'Fast' | 'Standard' | 'High') => {
    updateSetting('hdMode', mode);
  };

  // Switch layouts seamlessly based on currentTab setting
  if (currentTab === 'idcard' || currentTab === 'grid') {
    const now = new Date();
    const dateStr = now.toLocaleDateString();
    const timeStr = now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
    const dynamicTitle = settings?.scannerSubTab === 'idcard' 
      ? `CNIC ${dateStr} ${timeStr}` 
      : `Grid ${dateStr} ${timeStr}`;

    return (
      <CardScanner
        mode={currentTab}
        onClose={onClose}
        onChangeTab={onChangeTab}
        documentTitle={dynamicTitle}
      />
    );
  }

  return (
    <div className="w-full h-full relative" id="unified-scanner-wrapper">
      <input 
        type="file" 
        accept="image/*" 
        capture="environment" 
        ref={phoneCameraInputRef} 
        onChange={handlePhoneCameraFileChange} 
        className="hidden" 
      />
      <UnifiedViewfinder
        ref={viewfinderRef}
        mode={mode}
        aspectRatio={0.707} 
        quality={settings.hdMode}
        onClose={onClose}
        onChangeTab={handleTabChange}
        currentTab={mode}
        flashMode={settings.flashMode}
        onToggleFlash={handleToggleFlash}
        onUpdateSetting={handleUpdateSetting}
        onUpdateResolution={handleUpdateResolution}
        hdMode={settings.hdMode}
        settings={settings}
        activeSlotLabel={displaySlotLabel}
        onCaptureClick={onCaptureClick}
        onDoneClick={onDone}
        onFallbackUploadClick={onFallbackUpload}
        isBatchMode={settings.batchScan}
        onBatchToggle={onBatchToggle}
        batchCount={pages.length}
        isCapturing={isCapturing}
        showGrid={settings.showGrid}
        showGuidance={true}
        hideShutter={isGridViewVisible}
      />

      {isGridViewVisible && pages.length > 0 && (
        <GridView 
          pages={pages} 
          onDelete={(id) => onDeletePage?.(id)}
          onClose={() => setIsGridViewVisible(false)}
          onDone={() => {
            setIsGridViewVisible(false);
            if (onDone) onDone();
          }}
          scannerSubTab={settings.scannerSubTab}
          onUpdatePage={onUpdatePage}
          onReorderPages={onReorderPages}
        />
      )}
    </div>
  );
};

export default UnifiedScanner;
