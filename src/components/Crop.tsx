// AUDITED: Fixed canvas leaks and removed unused exports
import React, { useEffect, useRef } from 'react';
import { 
  X, Check, RotateCw, Contrast, Sun, SlidersHorizontal, 
  Maximize2, RefreshCw, ZoomIn, Focus, Layers, Sparkles,
  Crop as CropIcon
} from 'lucide-react';
import { PageCorners, ScanFilterType, PageAdjustments } from '../types';
import { useCropHook } from './CropHook';
import { globalRenderCountRef } from '../utils/renderStats';
import { getCSSFilterString } from '../utils/imageProcess';
import { useSharedSettings } from '../lib/useSharedSettings';

interface CropProps {
  imageSrc: string | Blob; // Can be a blob URL or a Blob object
  initialCorners?: PageCorners;
  initialRotation?: number;
  initialFilter?: ScanFilterType;
  initialAdjustments?: PageAdjustments;
  sourceType?: string;
  onSave: (finalBlob: Blob, corners: PageCorners, rotation: number, filter: ScanFilterType, adjustments: PageAdjustments) => void;
  onSaveAndNext?: (finalBlob: Blob, corners: PageCorners, rotation: number, filter: ScanFilterType, adjustments: PageAdjustments) => void;
  onCancel: () => void;
  onCropChange?: (newCorners: PageCorners) => void;
}

const PRESET_FILTERS = [
  { id: 'original', name: 'ORIGINAL', desc: 'No modifications' },
  { id: 'auto-enhance', name: 'ENHANCE', desc: 'Auto contrast & brightness' },
  { id: 'grayscale', name: 'GRAYSC', desc: 'Simple grey tones' },
  { id: 'pro-scan', name: 'PRO SCAN', desc: 'Fast Contrast & Threshold Grayscale' },
  { id: 'bw', name: 'B&W SCAN', desc: 'Classic binarization' },
  { id: 'noir', name: 'NOIR', desc: 'High-contrast monochrome' },
  { id: 'document', name: 'DOCUMENT', desc: 'High-contrast text' },
] as const;

function Crop({
  imageSrc,
  initialCorners,
  initialRotation = 0,
  initialFilter = 'original',
  initialAdjustments = { brightness: 0, contrast: 0, saturation: 0, grayscale: 0, sharpness: 0, shadows: 0, temperature: 0 },
  sourceType,
  onSave,
  onSaveAndNext,
  onCancel,
  onCropChange,
}: CropProps) {
  const { settings } = useSharedSettings();
  const renderCountRef = React.useRef(globalRenderCountRef);
  renderCountRef.current.current['Crop'] = (renderCountRef.current.current['Crop'] || 0) + 1;
  // console.log(`render Crop: ${renderCountRef.current.current['Crop']}x`);
  const {
    imgUrl,
    loading,
    isProcessing,
    corners,
    rotation,
    filter,
    setFilter,
    adjustments,
    setAdjustments,
    activeTab,
    setActiveTab,
    containerRef,
    imageRef,
    overlayStyle,
    handleRotate,
    handleResetCrop,
    handleAutoDetect,
    handlePointerDown,
    handlePointerMove,
    handlePointerUp,
    getAbsoluteCoords,
    handleApplyChanges,
    isAutoDetecting,
    showFlash,
    toast,
    imgLoaded,
    handleImageLoad,
    naturalAspect,
    scale,
    setScale,
    // Direct DOM fast styling updates
    tlDOMRef,
    trDOMRef,
    brDOMRef,
    blDOMRef,
    tDOMRef,
    bDOMRef,
    lDOMRef,
    rDOMRef,
    tlHalfDOMRef,
    trHalfDOMRef,
    blHalfDOMRef,
    brHalfDOMRef,
    ltHalfDOMRef,
    lbHalfDOMRef,
    rtHalfDOMRef,
    rbHalfDOMRef,
    polygonDOMRef,
    overlayPathDOMRef,
    zoomCircleDOMRef,
  } = useCropHook({
    imageSrc,
    initialCorners,
    initialRotation,
    initialFilter,
    initialAdjustments,
    onSave,
    onSaveAndNext,
    onCropChange,
    sourceType,
  });

  useEffect(() => {
    setActiveTab('crop');
  }, [imageSrc, setActiveTab]);
  
  const thumbnailCanvasRef = React.useRef<HTMLCanvasElement | null>(null);
  const imgDataRef = React.useRef<ImageBitmap | null>(null);

  useEffect(() => {
    return () => {
      if (thumbnailCanvasRef.current) {
        thumbnailCanvasRef.current.width = 0;
        thumbnailCanvasRef.current.height = 0;
        thumbnailCanvasRef.current = null;
      }
    };
  }, []);

  const requestDrawCanvas = React.useCallback((adj: any, flt: any) => {
    if (!imageRef.current || !imgDataRef.current) return;
    const canvas = imageRef.current;
    
    // Check if we already drew the base image. Use tag verification.
    if (canvas.dataset.drawn !== imgUrl) {
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.filter = 'none';
        ctx.drawImage(imgDataRef.current, 0, 0, canvas.width, canvas.height);
        canvas.dataset.drawn = imgUrl;
      }
    }
    
    // Apply visual filter via high-speed DOM CSS
    canvas.style.willChange = 'transform, filter';
    const cssFilter = getCSSFilterString(flt, adj, true);
    if (canvas.style.filter !== cssFilter) {
      canvas.style.filter = cssFilter;
    }
  }, [imageRef, imgUrl]);

  useEffect(() => {
    if (!imageRef.current || !imgUrl) return;
    
    const loadBitmap = async () => {
      try {
        const response = await fetch(imgUrl);
        const blob = await response.blob();
        const bitmap = await createImageBitmap(blob);
        imgDataRef.current = bitmap;
        
        const canvas = imageRef.current;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;

        const TARGET_DIM = 1080;
        let w = bitmap.width;
        let h = bitmap.height;
        const bitmapScale = Math.min(1, TARGET_DIM / Math.max(w, h));
        if (bitmapScale < 1) {
          w = Math.round(w * bitmapScale);
          h = Math.round(h * bitmapScale);
        }
        canvas.width = w;
        canvas.height = h;

        ctx.drawImage(bitmap, 0, 0, w, h);
        canvas.dataset.drawn = imgUrl; // Tag it so requestDrawCanvas knows it's current

        handleImageLoad();
      } catch (e) {
        console.error("Zero-Copy bitmap load fail:", e);
      }
    };
    
    loadBitmap();

    return () => {
      if (imgDataRef.current) {
        imgDataRef.current.close();
        imgDataRef.current = null;
      }
    };
  }, [imgUrl]);

  // Redraw when filter or adjustments change
  useEffect(() => {
    requestDrawCanvas(adjustments, filter);
  }, [filter, adjustments, requestDrawCanvas]);

  const { abs, initialPathD } = React.useMemo(() => {
    const abs = getAbsoluteCoords();
    const overlayWidth = overlayStyle.width ? parseFloat(String(overlayStyle.width)) : 100;
    const overlayHeight = overlayStyle.height ? parseFloat(String(overlayStyle.height)) : 100;
    const initialPathD = `M 0,0 L ${overlayWidth},0 L ${overlayWidth},${overlayHeight} L 0,${overlayHeight} Z M ${abs.tl.x},${abs.tl.y} L ${abs.tr.x},${abs.tr.y} L ${abs.br.x},${abs.br.y} L ${abs.bl.x},${abs.bl.y} Z`;
    return { abs, initialPathD };
  }, [getAbsoluteCoords, overlayStyle.width, overlayStyle.height]);

  // Keep slider values in a ref for direct high-speed DOM updates
  const adjustmentsRef = React.useRef({ ...adjustments });
  React.useEffect(() => {
    adjustmentsRef.current = { ...adjustments };
  }, [adjustments]);

  const updateLiveFiltersDirect = React.useCallback((adj: typeof adjustments) => {
    requestDrawCanvas(adj, filter);
  }, [requestDrawCanvas, filter]);

  // Determine if points or options are adjusted compared to initially provided properties
  const isAdjusted = React.useMemo(() => {
    const referenceCorners = initialCorners || {
      tl: { x: 5, y: 5 },
      tr: { x: 95, y: 5 },
      br: { x: 95, y: 95 },
      bl: { x: 5, y: 95 }
    };

    const cornersChanged = 
      Math.abs(corners.tl.x - referenceCorners.tl.x) > 0.05 ||
      Math.abs(corners.tl.y - referenceCorners.tl.y) > 0.05 ||
      Math.abs(corners.tr.x - referenceCorners.tr.x) > 0.05 ||
      Math.abs(corners.tr.y - referenceCorners.tr.y) > 0.05 ||
      Math.abs(corners.br.x - referenceCorners.br.x) > 0.05 ||
      Math.abs(corners.br.y - referenceCorners.br.y) > 0.05 ||
      Math.abs(corners.bl.x - referenceCorners.bl.x) > 0.05 ||
      Math.abs(corners.bl.y - referenceCorners.bl.y) > 0.05;

    const otherChanged = 
      rotation !== initialRotation ||
      filter !== initialFilter ||
      adjustments.brightness !== initialAdjustments.brightness ||
      adjustments.contrast !== initialAdjustments.contrast ||
      adjustments.saturation !== initialAdjustments.saturation ||
      adjustments.grayscale !== initialAdjustments.grayscale ||
      (adjustments.sharpness || 0) !== (initialAdjustments.sharpness || 0) ||
      (adjustments.shadows || 0) !== (initialAdjustments.shadows || 0) ||
      (adjustments.temperature || 0) !== (initialAdjustments.temperature || 0);

    return cornersChanged || otherChanged;
  }, [initialCorners, corners, rotation, initialRotation, filter, initialFilter, adjustments, initialAdjustments]);

  const saveBtnClass = isAdjusted
    ? 'bg-[var(--primary)] hover:opacity-90 text-white font-black shadow-lg shadow-[var(--primary)]/25 active:scale-95 border-none'
    : 'bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] text-[var(--text-secondary)] border border-[var(--border-color)] font-extrabold cursor-pointer active:scale-95';

  const scaleRef = useRef(1);
  scaleRef.current = scale;

  const [containerSize, setContainerSize] = React.useState({ width: 0, height: 0 });

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    
    const updateSize = () => {
      setContainerSize({
        width: container.clientWidth,
        height: container.clientHeight
      });
    };
    
    updateSize();
    const ro = new ResizeObserver(updateSize);
    ro.observe(container);
    return () => ro.disconnect();
  }, [containerRef]);

  const fittedDimensions = React.useMemo(() => {
    if (!naturalAspect || containerSize.width === 0 || containerSize.height === 0) {
      return { w: 100, h: 100 };
    }

    const widthLimit = containerSize.width * 0.9;
    const heightLimit = Math.max(100, Math.min(containerSize.height * 0.8, containerSize.height - 180));

    let W_u = 0;
    let H_u = 0;

    if (rotation % 180 === 0) {
      if (naturalAspect >= widthLimit / heightLimit) {
        W_u = widthLimit;
        H_u = W_u / naturalAspect;
      } else {
        H_u = heightLimit;
        W_u = H_u * naturalAspect;
      }
    } else {
      if (naturalAspect >= heightLimit / widthLimit) {
        W_u = heightLimit;
        H_u = W_u / naturalAspect;
      } else {
        H_u = widthLimit;
        W_u = H_u * naturalAspect;
      }
    }

    return { w: Math.round(W_u), h: Math.round(H_u) };
  }, [naturalAspect, containerSize, rotation]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    let initialDist = 0;
    let initialScale = 1;
    
    const handleTouchStart = (e: TouchEvent) => {
      if (e.touches.length === 2) {
        initialDist = Math.hypot(
          e.touches[0].clientX - e.touches[1].clientX,
          e.touches[0].clientY - e.touches[1].clientY
        );
        initialScale = scaleRef.current;
      }
    };

    const handleTouchMove = (e: TouchEvent) => {
      if (e.touches.length === 2) {
        e.preventDefault();
        const dist = Math.hypot(
          e.touches[0].clientX - e.touches[1].clientX,
          e.touches[0].clientY - e.touches[1].clientY
        );
        if (initialDist > 0) {
          const factor = dist / initialDist;
          const newScale = Math.max(0.5, Math.min(3, initialScale * factor));
          setScale(newScale);
        }
      }
    };

    container.addEventListener('touchstart', handleTouchStart, { passive: false });
    container.addEventListener('touchmove', handleTouchMove, { passive: false });
    
    return () => {
      container.removeEventListener('touchstart', handleTouchStart);
      container.removeEventListener('touchmove', handleTouchMove);
    };
  }, [containerRef]);

  return (
    <div className="fixed inset-0 bg-[var(--bg-primary)] z-50 flex flex-col items-stretch h-full overflow-hidden text-[var(--text-primary)] animate-crop-open" id="image-editor-root">
      {/* Header Panel */}
      <div className="bg-[var(--bg-card)] border-b border-[var(--border-color)] px-4 h-[56px] flex items-center justify-between select-none shrink-0 font-['Inter']">
        <div className="flex items-center gap-4">
          <button
            onClick={onCancel}
            id="editor-cancel-btn"
            disabled={isProcessing}
            className="p-2 w-10 h-10 flex items-center justify-center text-[var(--text-secondary)] hover:text-[var(--text-primary)] rounded-full hover:bg-[var(--bg-primary)] active:scale-95 duration-200 disabled:opacity-30 cursor-pointer transition-colors"
            title="Cancel edits"
          >
            <X className="w-5 h-5" />
          </button>
          <div className="flex items-center gap-4">
            <button
              type="button"
              onClick={() => {
                setFilter('original');
                const resetAdj = { brightness: 0, contrast: 0, saturation: 0, grayscale: 0, sharpness: 0, shadows: 0, temperature: 0 };
                setAdjustments(resetAdj);
                if (adjustmentsRef.current) {
                  adjustmentsRef.current = resetAdj as any;
                }
                requestDrawCanvas(resetAdj, 'original');
                setActiveTab('crop');
              }}
              id="editor-reset-all-btn"
              className="px-4 py-1.5 bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] active:scale-[0.98] transition-all text-xs font-['Inter'] font-medium tracking-wide rounded-full flex items-center justify-center gap-1.5 cursor-pointer border border-[var(--border-color)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]"
            >
              <RefreshCw className="w-3 h-3" />
              Reset
            </button>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <button
            onClick={() => handleApplyChanges(false)}
            disabled={loading || isProcessing}
            className={`flex items-center gap-2 px-6 h-9 rounded-full transition-all duration-300 text-xs font-['Inter'] font-medium tracking-wide shadow-md ${saveBtnClass} ${(loading || isProcessing) ? 'opacity-50 pointer-events-none' : ''}`}
            id="editor-save-changes-btn"
          >
            {isProcessing ? (
              <>
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                <span>APPLYING...</span>
              </>
            ) : (
              <>
                <Check className="w-3.5 h-3.5 stroke-[2]" />
                <span>APPLY</span>
              </>
            )}
          </button>

          {onSaveAndNext && (
            <button
              onClick={() => handleApplyChanges(true)}
              disabled={loading || isProcessing}
              className="flex items-center gap-2 bg-[var(--bg-card)] border border-[var(--border-color)] hover:border-[var(--primary)] text-[var(--text-primary)] font-['Inter'] font-medium px-6 h-9 rounded-full transition-all cursor-pointer active:scale-95 text-xs tracking-wide hover:text-[var(--primary)] disabled:opacity-50"
              id="editor-save-and-next-btn"
            >
              {isProcessing ? (
                <RefreshCw className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <span>NEXT</span>
              )}
            </button>
          )}
        </div>
      </div>

      {/* Main Viewport Workspace: Centers our image, overlay boxes and dragging logic */}
      <div 
        ref={containerRef}
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        className="flex-1 relative flex flex-col items-center justify-center p-4 bg-[var(--bg-primary)] touch-none overflow-hidden select-none"
        id="editor-viewport-sandbox"
        style={{ touchAction: 'none' }}
      >
        {/* Live Hardware-Accelerated SVG Filter Defs removed */}

        {loading ? (
          <div className="flex flex-col items-center gap-3 select-none">
            <RefreshCw className="w-8 h-8 animate-spin text-[var(--primary)]" />
            <span className="text-[var(--text-secondary)] text-[10px] font-black uppercase tracking-widest">LOADING IMAGE MATRIX...</span>
          </div>
        ) : (
          <div 
            className={`relative flex items-center justify-center select-none`} 
            style={{ 
              width: `${fittedDimensions.w}px`,
              height: `${fittedDimensions.h}px`,
              transformOrigin: 'center',
              transform: `rotate(${rotation}deg) scale(${scale * (filter !== 'original' ? 1.15 : 1.0)})`,
              transition: 'transform 0.25s cubic-bezier(0.16, 1, 0.3, 1)' 
            }}
          >
            <canvas
              ref={imageRef}
              className={`select-none rounded-sm transition-all duration-155 shadow-2xl display-block`}
              style={{
                width: '100%',
                height: '100%',
                objectFit: 'fill'
              }}
            />

            {activeTab === 'crop' && imgLoaded && (
              <svg 
                className="absolute pointer-events-none z-20 overflow-visible"
                style={overlayStyle}
              >
                {/* 50% Dark Overlay Outside Crop Area (Only Crop Area is Bright) with evenodd rule */}
                <path
                  ref={overlayPathDOMRef}
                  d={initialPathD}
                  fill="var(--bg-overlay)"
                  fillRule="evenodd"
                />

                <polygon
                  ref={polygonDOMRef}
                  points={`${abs.tl.x},${abs.tl.y} ${abs.tr.x},${abs.tr.y} ${abs.br.x},${abs.br.y} ${abs.bl.x},${abs.bl.y}`}
                  fill="color-mix(in srgb, var(--primary) 5%, transparent)"
                  stroke="var(--primary)"
                  strokeWidth="2.5"
                  className="transition-all"
                />
              </svg>
            )}

            {activeTab === 'crop' && imgLoaded && (
              <div 
                className="absolute pointer-events-none z-30 overflow-visible"
                style={overlayStyle}
              >
                {/* Top-Left Corner Handle */}
                <div
                  ref={tlDOMRef}
                  className="absolute pointer-events-auto cursor-grab active:cursor-grabbing transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tl.x}%`, top: `${corners.tl.y}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('tl', e)}
                >
                  <div 
                    className={`w-3.5 h-3.5 bg-white border-2 border-[var(--primary)] rounded-full shadow-2xl transition-all duration-200 ${showFlash ? 'animate-green-flash-glow' : 'hover:scale-110'}`}
                  />
                  {showFlash && (
                    <div 
                      className="absolute rounded-full bg-[var(--primary)] animate-ping opacity-75 z-0" 
                      style={{ width: '18px', height: '18px' }}
                    />
                  )}
                </div>

                {/* Top-Right Corner Handle */}
                <div
                  ref={trDOMRef}
                  className="absolute pointer-events-auto cursor-grab active:cursor-grabbing transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tr.x}%`, top: `${corners.tr.y}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('tr', e)}
                >
                  <div 
                    className={`w-3.5 h-3.5 bg-white border-2 border-[var(--primary)] rounded-full shadow-2xl transition-all duration-200 ${showFlash ? 'animate-green-flash-glow' : 'hover:scale-110'}`}
                  />
                  {showFlash && (
                    <div 
                      className="absolute rounded-full bg-[var(--primary)] animate-ping opacity-75 z-0" 
                      style={{ width: '18px', height: '18px' }}
                    />
                  )}
                </div>

                {/* Bottom-Right Corner Handle */}
                <div
                  ref={brDOMRef}
                  className="absolute pointer-events-auto cursor-grab active:cursor-grabbing transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.br.x}%`, top: `${corners.br.y}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('br', e)}
                >
                  <div 
                    className={`w-3.5 h-3.5 bg-white border-2 border-[var(--primary)] rounded-full shadow-2xl transition-all duration-200 ${showFlash ? 'animate-green-flash-glow' : 'hover:scale-110'}`}
                  />
                  {showFlash && (
                    <div 
                      className="absolute rounded-full bg-[var(--primary)] animate-ping opacity-75 z-0" 
                      style={{ width: '18px', height: '18px' }}
                    />
                  )}
                </div>

                {/* Bottom-Left Corner Handle */}
                <div
                  ref={blDOMRef}
                  className="absolute pointer-events-auto cursor-grab active:cursor-grabbing transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.bl.x}%`, top: `${corners.bl.y}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('bl', e)}
                >
                  <div 
                    className={`w-3.5 h-3.5 bg-white border-2 border-[var(--primary)] rounded-full shadow-2xl transition-all duration-200 ${showFlash ? 'animate-green-flash-glow' : 'hover:scale-110'}`}
                  />
                  {showFlash && (
                    <div 
                      className="absolute rounded-full bg-[var(--primary)] animate-ping opacity-75 z-0" 
                      style={{ width: '18px', height: '18px' }}
                    />
                  )}
                </div>

                <div
                  ref={tDOMRef}
                  className="absolute pointer-events-auto cursor-ns-resize transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${(corners.tl.x + corners.tr.x) / 2}%`, top: `${(corners.tl.y + corners.tr.y) / 2}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('t', e)}
                  title="Shift top edge parallel"
                >
                  <div className="w-5 h-1.5 rounded-full bg-[var(--primary)] border-[1.5px] border-white shadow-md hover:scale-110 active:scale-125 transition-transform" />
                </div>

                {/* Top Left Half-Edge Vector Control */}
                <div
                  ref={tlHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tl.x * 0.75 + corners.tr.x * 0.25}%`, top: `${corners.tl.y * 0.75 + corners.tr.y * 0.25}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('tl_half', e)}
                  title="Fine-tune top-left tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                {/* Top Right Half-Edge Vector Control */}
                <div
                  ref={trHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tl.x * 0.25 + corners.tr.x * 0.75}%`, top: `${corners.tl.y * 0.25 + corners.tr.y * 0.75}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('tr_half', e)}
                  title="Fine-tune top-right tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                <div
                  ref={bDOMRef}
                  className="absolute pointer-events-auto cursor-ns-resize transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${(corners.bl.x + corners.br.x) / 2}%`, top: `${(corners.bl.y + corners.br.y) / 2}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('b', e)}
                  title="Shift bottom edge parallel"
                >
                  <div className="w-5 h-1.5 rounded-full bg-[var(--primary)] border-[1.5px] border-white shadow-md hover:scale-110 active:scale-125 transition-transform" />
                </div>

                {/* Bottom Left Half-Edge Vector Control */}
                <div
                  ref={blHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.bl.x * 0.75 + corners.br.x * 0.25}%`, top: `${corners.bl.y * 0.75 + corners.br.y * 0.25}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('bl_half', e)}
                  title="Fine-tune bottom-left tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                {/* Bottom Right Half-Edge Vector Control */}
                <div
                  ref={brHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.bl.x * 0.25 + corners.br.x * 0.75}%`, top: `${corners.bl.y * 0.25 + corners.br.y * 0.75}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('br_half', e)}
                  title="Fine-tune bottom-right tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                <div
                  ref={lDOMRef}
                  className="absolute pointer-events-auto cursor-ew-resize transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${(corners.tl.x + corners.bl.x) / 2}%`, top: `${(corners.tl.y + corners.bl.y) / 2}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('l', e)}
                  title="Shift left edge parallel"
                >
                  <div className="w-1.5 h-5 rounded-full bg-[var(--primary)] border-[1.5px] border-white shadow-md hover:scale-110 active:scale-125 transition-transform" />
                </div>

                {/* Left Top Half-Edge Vector Control */}
                <div
                  ref={ltHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tl.x * 0.75 + corners.bl.x * 0.25}%`, top: `${corners.tl.y * 0.75 + corners.bl.y * 0.25}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('lt_half', e)}
                  title="Fine-tune left-top tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                {/* Left Bottom Half-Edge Vector Control */}
                <div
                  ref={lbHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tl.x * 0.25 + corners.bl.x * 0.75}%`, top: `${corners.tl.y * 0.25 + corners.bl.y * 0.75}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('lb_half', e)}
                  title="Fine-tune left-bottom tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                <div
                  ref={rDOMRef}
                  className="absolute pointer-events-auto cursor-ew-resize transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${(corners.tr.x + corners.br.x) / 2}%`, top: `${(corners.tr.y + corners.br.y) / 2}%`, width: 44, height: 44 }}
                  onPointerDown={(e) => handlePointerDown('r', e)}
                  title="Shift right edge parallel"
                >
                  <div className="w-1.5 h-5 rounded-full bg-[var(--primary)] border-[1.5px] border-white shadow-md hover:scale-110 active:scale-125 transition-transform" />
                </div>

                {/* Right Top Half-Edge Vector Control */}
                <div
                  ref={rtHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tr.x * 0.75 + corners.br.x * 0.25}%`, top: `${corners.tr.y * 0.75 + corners.br.y * 0.25}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('rt_half', e)}
                  title="Fine-tune right-top tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>

                {/* Right Bottom Half-Edge Vector Control */}
                <div
                  ref={rbHalfDOMRef}
                  className="absolute pointer-events-auto cursor-move transform -translate-x-1/2 -translate-y-1/2 flex items-center justify-center origin-center z-10"
                  style={{ left: `${corners.tr.x * 0.25 + corners.br.x * 0.75}%`, top: `${corners.tr.y * 0.25 + corners.br.y * 0.75}%`, width: 40, height: 40 }}
                  onPointerDown={(e) => handlePointerDown('rb_half', e)}
                  title="Fine-tune right-bottom tilt vector"
                >
                  <div className="w-1.5 h-1.5 rounded-full bg-[var(--primary)] border border-white shadow-sm hover:scale-110 active:scale-120 transition-transform" />
                </div>
              </div>
            )}

            <div 
              ref={zoomCircleDOMRef}
              className="absolute top-4 left-4 w-28 h-28 rounded-full border-4 border-[var(--primary)] bg-[var(--bg-card)] overflow-hidden shadow-2xl z-40 flex items-center justify-center select-none animate-fade-in"
              style={{ 
                display: 'none',
                backgroundImage: `url(${imgUrl})`,
                backgroundSize: '800% 800%',
                backgroundRepeat: 'no-repeat'
              }}
            >
              <div className="absolute w-full h-full flex items-center justify-center pointer-events-none">
                <div className="w-full h-0.5 bg-[var(--primary)]/50" />
                <div className="absolute h-full w-0.5 bg-[var(--primary)]/50" />
                <ZoomIn className="absolute bottom-2 text-[var(--primary)] w-4 h-4 text-center opacity-80" />
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Control Tabs Panel Toolbar */}
      <div className="bg-[var(--bg-card)] border-t border-[var(--border-color)] z-30 select-none shrink-0">
        {/* Navigation Tabs Header */}
        <div className="flex bg-[var(--bg-card)]/80 backdrop-blur-lg px-2 pt-2 pb-2.5 gap-2 border-b border-[var(--border-color)] rounded-t-[32px] shadow-[0_-4px_25px_rgba(0,0,0,0.08)] font-['Inter']">
          <button
            onClick={() => setActiveTab('crop')}
            id="editor-tab-crop"
            className={`tab flex-1 flex flex-col items-center justify-center gap-1.5 transition-all duration-300 cursor-pointer outline-none min-h-14 rounded-2xl ${
              activeTab === 'crop' ? 'bg-[var(--primary)] text-white shadow-md' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-primary)]'
            }`}
          >
            <div className={`transition-all duration-300 rounded-full px-5 py-0.5 flex items-center justify-center`}>
              <CropIcon className={`w-5 h-5 ${activeTab === 'crop' ? 'scale-110' : ''}`} />
            </div>
            <span className="text-[10px] font-medium uppercase tracking-widest">Crop</span>
          </button>

          <button
            onClick={() => setActiveTab('ai')}
            id="editor-tab-ai"
            className={`tab flex-1 flex flex-col items-center justify-center gap-1.5 transition-all duration-300 cursor-pointer outline-none min-h-14 rounded-2xl ${
              activeTab === 'ai' ? 'bg-[var(--primary)] text-white shadow-md' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-primary)]'
            }`}
          >
            <div className={`transition-all duration-300 rounded-full px-5 py-0.5 flex items-center justify-center`}>
              <Sparkles className={`w-5 h-5 ${activeTab === 'ai' ? 'scale-110 text-white' : 'text-purple-500'}`} />
            </div>
            <span className="text-[10px] font-medium uppercase tracking-widest">AI</span>
          </button>

          <button
            onClick={() => setActiveTab('filter')}
            id="editor-tab-filter"
            className={`tab flex-1 flex flex-col items-center justify-center gap-1.5 transition-all duration-300 cursor-pointer outline-none min-h-14 rounded-2xl ${
              activeTab === 'filter' ? 'bg-[var(--primary)] text-white shadow-md' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-primary)]'
            }`}
          >
            <div className={`transition-all duration-300 rounded-full px-5 py-0.5 flex items-center justify-center`}>
              <Contrast className={`w-5 h-5 ${activeTab === 'filter' ? 'scale-110' : ''}`} />
            </div>
            <span className="text-[10px] font-medium uppercase tracking-widest">Filters</span>
          </button>

          <button
            onClick={() => setActiveTab('adjust')}
            id="editor-tab-adjust"
            className={`tab flex-1 flex flex-col items-center justify-center gap-1.5 transition-all duration-300 cursor-pointer outline-none min-h-14 rounded-2xl ${
              activeTab === 'adjust' ? 'bg-[var(--primary)] text-white shadow-md' : 'text-[var(--text-secondary)] hover:bg-[var(--bg-primary)]'
            }`}
          >
            <div className={`transition-all duration-300 rounded-full px-5 py-0.5 flex items-center justify-center`}>
              <SlidersHorizontal className={`w-5 h-5 ${activeTab === 'adjust' ? 'scale-110' : ''}`} />
            </div>
            <span className="text-[10px] font-medium uppercase tracking-widest">Adjust</span>
          </button>
        </div>

        {/* Tab Body Options details (reduced by 30%) */}
        <div className="p-2 max-w-lg mx-auto font-['Inter']">
          <div className="h-[88px] w-full flex flex-col justify-center font-['Inter'] overflow-hidden">
             {activeTab === 'crop' && (
              <div className="flex flex-col gap-2 w-full max-w-md mx-auto select-none p-1 font-['Inter']">
                <div className="grid grid-cols-3 gap-2">
                  <button
                    type="button"
                    onClick={handleRotate}
                    id="editor-rotate-btn"
                    className="h-[72px] bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] active:scale-[0.97] transition-all text-[10px] font-medium tracking-widest uppercase rounded-2xl flex flex-col items-center justify-center gap-1 cursor-pointer shadow-sm border border-[var(--border-color)] duration-200 font-['Inter']"
                    title="Rotate clockwise"
                  >
                    <RotateCw className="w-4 h-4 text-[var(--primary)]" />
                    <span>Rotate</span>
                  </button>

                  <button
                    type="button"
                    onClick={handleResetCrop}
                    id="editor-full-border-btn"
                    className="h-[72px] bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] active:scale-[0.97] transition-all text-[10px] font-medium tracking-widest uppercase rounded-2xl flex flex-col items-center justify-center gap-1 cursor-pointer shadow-sm border border-[var(--border-color)] duration-200 font-['Inter']"
                    title="Widen crop completely to borders"
                  >
                    <Maximize2 className="w-4 h-4 text-[var(--primary)]" />
                    <span>Full</span>
                  </button>

                  <button
                    type="button"
                    onClick={() => handleAutoDetect(false)}
                    id="editor-auto-detect-btn"
                    disabled={isAutoDetecting}
                    className="h-[72px] bg-[var(--bg-primary)] hover:bg-[var(--bg-card)] active:scale-[0.97] transition-all text-[10px] font-medium tracking-widest uppercase rounded-2xl flex flex-col items-center justify-center gap-1 cursor-pointer shadow-sm border border-[var(--border-color)] duration-200 disabled:opacity-50 font-['Inter']"
                    title="Automatically detect page corners"
                  >
                    {isAutoDetecting ? (
                      <RefreshCw className="w-4 h-4 text-[var(--primary)] animate-spin" />
                    ) : (
                      <Layers className="w-4 h-4 text-[var(--primary)]" />
                    )}
                    <span>{isAutoDetecting ? "..." : "Auto"}</span>
                  </button>
                </div>
              </div>
            )}

            {activeTab === 'ai' && (
              <div className="flex flex-col gap-2 w-full max-w-md mx-auto select-none p-2 font-['Inter'] bg-purple-500/5 border border-purple-500/10 rounded-2xl animate-in fade-in duration-200">
                <div className="flex items-center gap-3 justify-between">
                  <div className="flex flex-col text-left">
                    <span className="text-[10px] font-black uppercase text-purple-500 tracking-widest font-mono flex items-center gap-1">
                      <Sparkles className="w-3.5 h-3.5 fill-purple-500/10 animate-pulse" /> Gemini AI Vision
                    </span>
                    <span className="text-[9px] text-[var(--text-secondary)] leading-normal max-w-[240px]">
                      {settings.offlineMode 
                        ? "AI edge detection is unavailable in Offline-Only mode." 
                        : "Detect borders, correct perspectives, and crop with extreme precision."}
                    </span>
                  </div>
                  <button
                    type="button"
                    onClick={() => handleAutoDetect(true)}
                    id="editor-ai-detect-btn"
                    disabled={isAutoDetecting || settings.offlineMode}
                    className={`px-4 py-2 text-white font-bold text-[10px] uppercase tracking-wider rounded-xl cursor-pointer hover:opacity-95 active:scale-[0.97] transition-all flex items-center justify-center gap-1.5 shadow-md min-h-[38px] ${
                      settings.offlineMode 
                        ? 'bg-zinc-700 pointer-events-none opacity-40 border border-zinc-650' 
                        : 'bg-gradient-to-r from-purple-600 to-pink-600 border border-purple-500/30'
                    }`}
                    title={settings.offlineMode ? "AI detection is disabled in Offline-Only mode" : "Automatically detect page corners using Gemini AI"}
                  >
                    {isAutoDetecting ? (
                      <RefreshCw className="w-3.5 h-3.5 animate-spin" />
                    ) : (
                      <Sparkles className="w-3.5 h-3.5 fill-white/10" />
                    )}
                    <span>{isAutoDetecting ? "..." : "Detect"}</span>
                  </button>
                </div>
              </div>
            )}

            {activeTab === 'filter' && (
              <div className="grid grid-rows-2 grid-flow-col gap-1 overflow-x-auto px-1 select-none animate-in fade-in duration-200 max-w-md mx-auto no-scrollbar font-['Inter']">
                {PRESET_FILTERS.map((preset) => {
                  const isActive = filter === preset.id;
                  return (
                    <button
                      key={preset.id}
                      type="button"
                      onClick={() => setFilter(preset.id)}
                      id={`editor-filter-btn-${preset.id}`}
                      className={`relative px-3 py-1.5 min-w-[90px] h-[38px] border transition-all duration-300 text-center cursor-pointer rounded-xl flex items-center justify-center font-['Inter'] font-medium ${
                        isActive
                          ? 'bg-[var(--primary)] text-white shadow-lg shadow-[var(--primary)]/20 border-transparent scale-[1.02]'
                          : 'bg-[var(--bg-primary)] border-[var(--border-color)] text-[var(--text-primary)] hover:bg-[var(--bg-card)]'
                      }`}
                    >
                      <span className="text-[10px] uppercase tracking-wider font-semibold font-['Inter'] truncate w-full">{preset.name}</span>
                    </button>
                  );
                })}
              </div>
            )}

            {activeTab === 'adjust' && (
              <div className="grid grid-cols-2 gap-x-5 gap-y-1.5 animate-in fade-in duration-200 max-w-md mx-auto w-full px-2">
                <SliderControl 
                  icon={<Sun className="w-4 h-4 text-[var(--text-secondary)]" />} 
                  label="Brightness" 
                  initialValue={adjustments.brightness + 100} 
                  min={0}
                  max={200}
                  onChange={(v) => {
                    adjustmentsRef.current.brightness = v - 100;
                    updateLiveFiltersDirect(adjustmentsRef.current);
                  }}
                  onChangeEnd={(v) => {
                    setAdjustments({ ...adjustmentsRef.current, brightness: v - 100 });
                  }}
                />
                <SliderControl 
                  icon={<Contrast className="w-4 h-4 text-[var(--text-secondary)]" />} 
                  label="Contrast" 
                  initialValue={adjustments.contrast + 100} 
                  min={0}
                  max={200}
                  onChange={(v) => {
                    adjustmentsRef.current.contrast = v - 100;
                    updateLiveFiltersDirect(adjustmentsRef.current);
                  }}
                  onChangeEnd={(v) => {
                    setAdjustments({ ...adjustmentsRef.current, contrast: v - 100 });
                  }}
                />
                <SliderControl 
                  icon={<SlidersHorizontal className="w-4 h-4 text-[var(--text-secondary)]" />} 
                  label="Saturation" 
                  initialValue={adjustments.saturation + 100} 
                  min={0}
                  max={200}
                  onChange={(v) => {
                    adjustmentsRef.current.saturation = v - 100;
                    updateLiveFiltersDirect(adjustmentsRef.current);
                  }}
                  onChangeEnd={(v) => {
                    setAdjustments({ ...adjustmentsRef.current, saturation: v - 100 });
                  }}
                />
                <SliderControl 
                  icon={<Focus className="w-4 h-4 text-[var(--text-secondary)]" />} 
                  label="Grayscale" 
                  initialValue={adjustments.grayscale || 0} 
                  min={0}
                  max={100}
                  onChange={(v) => {
                    adjustmentsRef.current.grayscale = v;
                    updateLiveFiltersDirect(adjustmentsRef.current);
                  }}
                  onChangeEnd={(v) => {
                    setAdjustments({ ...adjustmentsRef.current, grayscale: v });
                  }}
                />
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Floating local toast notifications */}
      {toast && (
        <div className="absolute bottom-32 left-1/2 -translate-x-1/2 bg-[var(--bg-card)] border border-[var(--border-color)] text-[var(--text-primary)] px-5 py-3 rounded-2xl flex items-center gap-3 z-50 shadow-2xl font-['Inter'] text-xs font-medium uppercase tracking-widest animate-in fade-in slide-in-from-bottom-4 duration-300" id="editor-local-toast">
          <span className="w-2 h-2 rounded-full bg-[var(--primary)] animate-pulse shrink-0" />
          <span>{toast}</span>
        </div>
      )}
    </div>
  );
}

function SliderControl({ 
  icon, 
  label, 
  initialValue, 
  min = -100,
  max = 100,
  onChange, 
  onChangeEnd 
}: { 
  icon: React.ReactNode, 
  label: string, 
  initialValue: number, 
  min?: number,
  max?: number,
  onChange: (v: number) => void, 
  onChangeEnd: (v: number) => void 
}) {
  const [val, setVal] = React.useState(initialValue);
  
  React.useEffect(() => {
    setVal(initialValue);
  }, [initialValue]);

  const pct = ((val - min) / (max - min)) * 100;

  return (
    <div className="space-y-0.5 select-none w-full font-['Inter']">
      <div className="flex justify-between items-center text-[10px] font-medium text-[var(--text-secondary)] uppercase tracking-widest font-['Inter']">
        <span className="flex items-center gap-1.5 text-[var(--text-primary)] font-['Inter'] font-semibold">
          {icon} {label}
        </span>
        <span className="text-[var(--primary)] text-[10px] font-semibold font-['Inter']">
          {val > 0 ? `+${val}` : val}%
        </span>
      </div>
      <div className="relative flex items-center h-6">
        <input
          type="range"
          min={min}
          max={max}
          value={val}
          style={{
            background: `linear-gradient(to right, var(--primary) 0%, var(--primary) ${pct}%, var(--border-color) ${pct}%, var(--border-color) 100%)`
          }}
          onChange={(e) => {
            const num = Number(e.target.value);
            setVal(num);
            
            // Debounce the heavy recalculation callback
            const timerId = (window as any)[`_slider_timer_${label}`];
            if (timerId) clearTimeout(timerId);
            (window as any)[`_slider_timer_${label}`] = setTimeout(() => {
              onChange(num);
            }, 200);
          }}
          onPointerUp={(e) => {
            const timerId = (window as any)[`_slider_timer_${label}`];
            if (timerId) clearTimeout(timerId);
            onChangeEnd(Number(e.currentTarget.value));
          }}
          onTouchEnd={(e) => {
            const timerId = (window as any)[`_slider_timer_${label}`];
            if (timerId) clearTimeout(timerId);
            onChangeEnd(Number(e.currentTarget.value));
          }}
          className="w-full h-1.5 rounded-lg appearance-none cursor-pointer outline-none bg-[var(--border-color)]
          [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:h-[20px] [&::-webkit-slider-thumb]:w-[20px] [&::-webkit-slider-thumb]:rounded-full [&::-webkit-slider-thumb]:bg-[var(--primary)] [&::-webkit-slider-thumb]:shadow-lg [&::-webkit-slider-thumb]:shadow-[var(--primary)]/20 [&::-webkit-slider-thumb]:border-none [&::-webkit-slider-thumb]:active:scale-110 [&::-webkit-slider-thumb]:transition-transform
          [&::-moz-range-thumb]:appearance-none [&::-moz-range-thumb]:h-[20px] [&::-moz-range-thumb]:w-[20px] [&::-moz-range-thumb]:rounded-full [&::-moz-range-thumb]:bg-[var(--primary)] [&::-moz-range-thumb]:shadow-lg [&::-moz-range-thumb]:shadow-[var(--primary)]/20 [&::-moz-range-thumb]:border-none [&::-moz-range-thumb]:active:scale-110 [&::-moz-range-thumb]:transition-transform"
        />
      </div>
    </div>
  );
}

export default React.memo(Crop);
