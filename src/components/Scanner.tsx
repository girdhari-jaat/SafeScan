// AUDITED: Fixed canvas leaks and removed unused exports
import React, { useEffect, useState } from 'react';
import { X } from 'lucide-react';
import { PageCorners } from '../types';
import { PAPER_RATIOS, CARD_RATIOS } from '../constants';
import { useScannerHook } from './ScannerHook';
import { useCamera } from '../contexts/CameraContext';
import { UnifiedViewfinder } from './UnifiedViewfinder';
import { getImageBlob } from '../utils/db';

interface ScannerProps {
  onCapture: (imageBlob: Blob, isBatch: boolean, corners: PageCorners, forceCrop?: boolean) => void;
  onFallbackUpload: () => void;
  format?: 'A4' | 'LANDSCAPE' | 'CNIC';
  onFormatChange?: (format: 'A4' | 'LANDSCAPE' | 'CNIC') => void;
  onClose: () => void;
  onDone?: () => void;
  pages?: any;
  onDeletePage?: (pageId: string) => void;
  onRetakePage?: (pageId: string, blob: Blob) => void;
  currentTab?: 'paper' | 'idcard' | 'grid';
  onChangeTab?: (tab: 'paper' | 'idcard' | 'grid') => void;
  onDonePage?: (pageId: string) => void;
}

interface GridViewProps {
  pages: any[];
  onDelete: (id: string) => void;
  onClose: () => void;
  onDone: () => void;
  scannerSubTab?: string;
}

const GridView = React.memo(({ pages, onDelete, onClose, onDone, scannerSubTab }: GridViewProps) => {
  const isCardMode = scannerSubTab === 'card' || scannerSubTab === 'grid';
  const designRatio = isCardMode ? CARD_RATIOS.LANDSCAPE : PAPER_RATIOS.A4;

  return (
    <div className="fixed inset-0 z-[60] bg-[var(--bg-primary)] flex flex-col animate-in fade-in duration-300">
      <div className="px-6 py-8 flex items-center justify-between border-b border-[var(--border-color)] shrink-0">
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
        {pages.map((page, idx) => (
          <div key={page.id} className="flex flex-col gap-2 group">
            <div style={{ aspectRatio: designRatio }} className="relative bg-[var(--bg-card)] rounded-xl border border-[var(--border-color)] overflow-hidden shadow-2xl group-hover:border-[var(--primary)]/50 transition-all">
               <ThumbnailImage imageId={page.originalImageId} />
               <div className="absolute top-2 right-2 flex gap-1">
                 <button 
                   onClick={() => onDelete(page.id)}
                   className="w-8 h-8 rounded-full bg-rose-500 text-white flex items-center justify-center shadow-lg cursor-pointer transform scale-0 group-hover:scale-100 transition-transform duration-200"
                 >
                   <X size={14} strokeWidth={3} />
                 </button>
               </div>
               <div className="absolute bottom-2 left-2 w-6 h-6 rounded-full bg-[var(--primary)] text-white text-[10px] font-black flex items-center justify-center shadow-xl border-2 border-[var(--bg-primary)]">
                 {idx + 1}
               </div>
            </div>
          </div>
        ))}
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
    </div>
  );
});

const ThumbnailImage = React.memo(({ imageId }: { imageId: string }) => {
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
  return url ? <img src={url} className="w-full h-full object-cover" /> : null;
});

const ThumbnailItem = React.memo(({ page, idx, onDelete, scannerSubTab }: { page: any, idx: number, onDelete: any, scannerSubTab?: string }) => {
  const isCardMode = scannerSubTab === 'card' || scannerSubTab === 'grid';
  const designRatio = isCardMode ? CARD_RATIOS.LANDSCAPE : PAPER_RATIOS.A4;
  return (
    <div 
      style={{ aspectRatio: designRatio }}
      className="relative shrink-0 w-16 bg-[var(--bg-card)] rounded-lg border border-[var(--border-color)] overflow-hidden shadow-lg group active:scale-95 transition-all"
    >
      <ThumbnailImage imageId={page.originalImageId} />
      
      <div className="absolute top-0.5 right-0.5 w-4 h-4 rounded-full bg-[var(--primary)] text-white text-[8px] font-black flex items-center justify-center border border-[var(--bg-card)] shadow-xl">
        {idx + 1}
      </div>
      <button
        onClick={() => onDelete?.(page.id)}
        className="absolute inset-0 bg-rose-500/80 opacity-0 group-hover:opacity-100 flex items-center justify-center text-white transition-opacity cursor-pointer border-none"
      >
        <X size={14} className="stroke-[3px]" />
      </button>
    </div>
  );
});
ThumbnailItem.displayName = 'ThumbnailItem';

function Scanner({ 
  onCapture, 
  onClose,
  onDone, 
  pages, 
  onDeletePage,
  currentTab,
  onChangeTab,
  format
}: ScannerProps) {
  const viewfinderRef = React.useRef<any>(null);
  const currentRatio = (format === 'LANDSCAPE' || format === 'CNIC') ? CARD_RATIOS.LANDSCAPE : PAPER_RATIOS.A4;

  const {
    videoRef,
    canvasRef,
    phoneCameraInputRef,
    flashMode,
    hdMode,
    isCapturing,
    settings,
    updateSetting,
    updateResolution,
    handlePhoneCameraFileChange,
    captureFrame,
    streamRef,
    toggleFlash,
  } = useScannerHook({ onCapture });

  const [isGridViewVisible, setIsGridViewVisible] = useState(false);
  const { detectedCorners } = useCamera();

  const totalPagesInDoc = (pages?.length || 0);
  const overlayCanvasRef = React.useRef<HTMLCanvasElement>(null);
  const overlayFrameRef = React.useRef<HTMLDivElement>(null);
  const [camProps, setCamProps] = React.useState<{
    width: number;
    height: number;
    aspectRatio: number;
    facingMode: string;
    isPortrait: boolean;
  } | null>(null);

  const handleCaptureClick = () => {
    if (!isCapturing) {
      captureFrame();
    }
  };

  // Step 1: Detect camera properties from streamRef.current or videoRef.current to avoid requesting a second stream
  React.useEffect(() => {
    let active = true;
    let checkInterval: any;

    const detectProps = () => {
      if (!active) return;
      try {
        let track: MediaStreamTrack | null = null;
        if (streamRef?.current) {
          track = streamRef.current.getVideoTracks()[0];
        } else if (videoRef?.current && videoRef.current.srcObject) {
          const stream = videoRef.current.srcObject as MediaStream;
          track = stream.getVideoTracks ? stream.getVideoTracks()[0] : null;
        }

        if (track) {
          const trackSettings = track.getSettings();
          const width = trackSettings.width || 1280;
          const height = trackSettings.height || 720;
          const aspectRatio = trackSettings.aspectRatio || (width / height);
          const facingMode = trackSettings.facingMode || 'environment';
          const isPortrait = window.innerHeight > window.innerWidth;

          setCamProps({
            width,
            height,
            aspectRatio,
            facingMode,
            isPortrait
          });
          
          // Slow down the check once successfully detected
          clearInterval(checkInterval);
          checkInterval = setInterval(detectProps, 2000);
        } else {
          // Smart default properties while stream is initialising to prevent blocked layout
          setCamProps(prev => prev || {
            width: 1280,
            height: 720,
            aspectRatio: 1.777,
            facingMode: 'environment',
            isPortrait: window.innerHeight > window.innerWidth
          });
        }
      } catch (err) {
        console.warn('Error reading camera properties:', err);
      }
    };

    checkInterval = setInterval(detectProps, 300);
    detectProps();

    return () => {
      active = false;
      clearInterval(checkInterval);
    };
  }, [streamRef, videoRef]);

  const [viewfinderSize, setViewfinderSize] = React.useState({ width: 0, height: 0 });

  const updateSizes = React.useCallback(() => {
    const workbench = document.getElementById('paper-scanner-workbench');
    if (!workbench) return;

    // Determine spacing
    const marginX = window.innerWidth < 768 ? 24 : 48;
    // Settings/header is around 70px height, controls/footer is around 120px height
    const headerSpacing = 74;
    const footerSpacing = 114;
    
    const maxW = workbench.clientWidth - marginX;
    const maxH = workbench.clientHeight - headerSpacing - footerSpacing;

    if (maxW <= 0 || maxH <= 0) return;

    let targetRatio = PAPER_RATIOS.A4; // default A4 portrait (0.7072)
    if (camProps) {
      const baseRatio = camProps.aspectRatio > 1 ? camProps.aspectRatio : (1 / camProps.aspectRatio);
      targetRatio = 1 / baseRatio;
    }
    const currentRatio = maxW / maxH;

    let w = 0;
    let h = 0;

    if (currentRatio > targetRatio) {
      // Container is wider than targetRatio, constrain by height
      h = maxH;
      w = h * targetRatio;
    } else {
      // Container is taller than targetRatio, constrain by width
      w = maxW;
      h = w / targetRatio;
    }

    setViewfinderSize({
      width: Math.round(w),
      height: Math.round(h)
    });
  }, [camProps]);

  React.useEffect(() => {
    const workbench = document.getElementById('paper-scanner-workbench');
    if (!workbench) return;

    const observer = new ResizeObserver(() => {
      updateSizes();
    });
    observer.observe(workbench);
    updateSizes();

    return () => {
      observer.disconnect();
    };
  }, [updateSizes]);

  // Step 2 & 3: Get overlay size keeping standard ratio locked and scale dynamically with 5% padding
  const getOverlayDimensions = React.useCallback(() => {
    const previewWidth = viewfinderSize.width > 0 ? viewfinderSize.width : window.innerWidth;
    const previewHeight = viewfinderSize.height > 0 ? viewfinderSize.height : window.innerHeight;

    // To have exactly 5% padding on all sides (left, right, top, bottom), the overlay box
    // should take up exactly 90% of the viewfinder block (total 100% - 5% left - 5% right = 90% width).
    const frameWidth = previewWidth * 0.90;
    const frameHeight = previewHeight * 0.90;

    return {
      width: Math.round(frameWidth),
      height: Math.round(frameHeight),
      previewWidth,
      previewHeight
    };
  }, [viewfinderSize]);

  // Synchronously update overlay frame element layout style
  React.useEffect(() => {
    const updateOverlayFrame = () => {
      const overlayFrame = overlayFrameRef.current;
      if (!overlayFrame) return;

      const { width, height } = getOverlayDimensions();
      overlayFrame.style.width = width + 'px';
      overlayFrame.style.height = height + 'px';
    };

    const workbench = document.getElementById('paper-scanner-workbench');
    if (!workbench) return;

    const observer = new ResizeObserver(() => {
      updateOverlayFrame();
    });
    observer.observe(workbench);
    updateOverlayFrame();

    return () => {
      observer.disconnect();
    };
  }, [getOverlayDimensions]);

  // Synchronize detected boundaries & custom dynamic A4 overlay to overlay canvas in screen space
  React.useEffect(() => {
    const canvas = overlayCanvasRef.current;
    if (!canvas || !camProps) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animFrameId: number;

    const drawCanvasFrame = () => {
      const rect = canvas.getBoundingClientRect();
      const dtw = Math.round(rect.width);
      const dth = Math.round(rect.height);

      if (canvas.width !== dtw || canvas.height !== dth) {
        canvas.width = dtw;
        canvas.height = dth;
      }

      ctx.clearRect(0, 0, dtw, dth);

      // 1. Draw detected boundaries
      if (detectedCorners && (detectedCorners as any).tl && settings.autoDetectEnabled) {
        const p0 = { x: (detectedCorners.tl.x / 100) * dtw, y: (detectedCorners.tl.y / 100) * dth };
        const p1 = { x: (detectedCorners.tr.x / 100) * dtw, y: (detectedCorners.tr.y / 100) * dth };
        const p2 = { x: (detectedCorners.br.x / 100) * dtw, y: (detectedCorners.br.y / 100) * dth };
        const p3 = { x: (detectedCorners.bl.x / 100) * dtw, y: (detectedCorners.bl.y / 100) * dth };

        ctx.beginPath();
        ctx.moveTo(p0.x, p0.y);
        ctx.lineTo(p1.x, p1.y);
        ctx.lineTo(p2.x, p2.y);
        ctx.lineTo(p3.x, p3.y);
        ctx.closePath();

        ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim();
        ctx.lineWidth = 3;
        ctx.lineJoin = 'round';
        ctx.stroke();

        if (currentTab !== 'idcard') {
          ctx.fillStyle = `color-mix(in srgb, ${getComputedStyle(document.documentElement).getPropertyValue('--primary').trim()} 12%, transparent)`;
          ctx.fill();
        }

        // Draw pin circles
        const points = [p0, p1, p2, p3];
        points.forEach(p => {
          ctx.beginPath();
          ctx.arc(p.x, p.y, 8, 0, 2 * Math.PI);
          ctx.fillStyle = getComputedStyle(document.documentElement).getPropertyValue('--primary').trim();
          ctx.fill();
          ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--bg-card').trim();
          ctx.lineWidth = 1.5;
          ctx.stroke();
        });
      }

      // 2. Centering grid/overlay box drawing removed from canvas to let the UnifiedViewfinder React/HTML overlay be the single, polished control guide overlay on the screen.

      animFrameId = requestAnimationFrame(drawCanvasFrame);
    };

    animFrameId = requestAnimationFrame(drawCanvasFrame);

    return () => {
      cancelAnimationFrame(animFrameId);
    };
  }, [camProps, detectedCorners, settings.showGrid, settings.autoDetectEnabled, getOverlayDimensions]);

  return (
    <div className="w-full h-full flex flex-col relative min-h-0" id="paper-scanner-workbench">
      <div className="relative flex-1 flex flex-col md:flex-row items-stretch min-h-0">
        <div className="relative flex-1 bg-black flex flex-col items-center justify-center overflow-hidden min-h-0 select-none">
          
          {/* Hidden canvas & phone camera file inputs */}
          <canvas ref={canvasRef} className="hidden" />
          <input 
            ref={phoneCameraInputRef}
            type="file" 
            accept="image/*" 
            onChange={handlePhoneCameraFileChange}
            className="hidden" 
          />
          <UnifiedViewfinder
            ref={viewfinderRef}
            mode={currentTab === 'paper' ? 'paper' : 'idcard'}
            aspectRatio={currentRatio}
            quality={hdMode as any}
            onClose={onClose}
            onChangeTab={onChangeTab}
            currentTab={currentTab}
            flashMode={flashMode}
            onToggleFlash={toggleFlash}
            settings={settings}
            onUpdateSetting={updateSetting}
            onUpdateResolution={updateResolution}
            hdMode={hdMode}
            onCaptureClick={handleCaptureClick}
            onFallbackUploadClick={() => phoneCameraInputRef.current?.click()}
            isBatchMode={settings?.batchScan}
            onBatchToggle={() => {
              if (totalPagesInDoc > 0) {
                setIsGridViewVisible(prev => !prev);
              } else {
                updateSetting('batchScan', !settings?.batchScan);
              }
            }}
            batchCount={totalPagesInDoc}
            isCapturing={isCapturing}
          />
        </div>
      </div>

      {isGridViewVisible && pages && (
        <GridView 
          pages={pages} 
          onDelete={(id) => onDeletePage?.(id)}
          onClose={() => setIsGridViewVisible(false)}
          onDone={() => {
            setIsGridViewVisible(false);
            if (onDone) onDone();
          }}
          scannerSubTab={settings?.scannerSubTab}
        />
      )}

    </div>
  );
}

export default React.memo(Scanner);
