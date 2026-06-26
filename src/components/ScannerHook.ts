// AUDITED: Fixed canvas leaks and removed unused exports
import { useRef, useState, useCallback, ChangeEvent } from 'react';
import { PageCorners } from '../types';
import { PAPER_RATIOS, CARD_RATIOS } from '../constants';
import { saveImageBlob, savePageMeta } from '../utils/db';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useCamera } from '../contexts/CameraContext';
import { getDefaultQuad } from '../utils/edgeDetection';


// Web Audio API Shutter Click Synthesizer
const playShutterSound = () => {
  try {
    const AudioContext = window.AudioContext || (window as any).webkitAudioContext;
    if (!AudioContext) return;
    const ctx = new AudioContext();
    const bufferSize = ctx.sampleRate * 0.1; // 100ms
    const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    const data = buffer.getChannelData(0);
    for (let i = 0; i < bufferSize; i++) {
      data[i] = Math.random() * 2 - 1;
    }
    const noise = ctx.createBufferSource();
    noise.buffer = buffer;
    const filter = ctx.createBiquadFilter();
    filter.type = 'bandpass';
    filter.frequency.value = 1000;
    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0.5, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.08);
    noise.connect(filter);
    filter.connect(gain);
    gain.connect(ctx.destination);
    noise.start();
  } catch (err) {
  }
};

interface UseScannerHookProps {
  onCapture: (imageBlob: Blob, isBatch: boolean, corners: PageCorners, forceCrop?: boolean, needsDetection?: boolean) => void;
}

export function useScannerHook({ onCapture }: UseScannerHookProps) {
  const { stream, videoTrack, cameraError, isReady: cameraIsReady, supportsTorch, restartCamera, applyFocus, videoRef, canvasRef, detectedCorners, setDetectedCorners } = useCamera();
  const [cameraErrorLocal, setCameraErrorLocal] = useState(false);
  const cameraErrorVal = cameraError || cameraErrorLocal;
  const phoneCameraInputRef = useRef<HTMLInputElement>(null);
  const settingsRef = useRef<HTMLDivElement>(null);

  const [batchCount, setBatchCount] = useState(0);
  const [isCapturing, setIsCapturing] = useState(false);
  const isCameraReady = cameraIsReady;
  
  // Settings state with shared hook
  const { settings, updateSetting: _updateSetting } = useSharedSettings();

  const isBatchMode = settings.batchScan;
  const flashMode = settings.flashMode;
  const hdMode = settings.hdMode;
  
  const setFlashMode = useCallback((val: 'off' | 'auto' | 'torch') => _updateSetting('flashMode', val), [_updateSetting]);
  const setIsBatchMode = useCallback((val: boolean) => _updateSetting('batchScan', val), [_updateSetting]);
  const setHdMode = useCallback((val: 'Fast' | 'Standard' | 'High') => _updateSetting('hdMode', val), [_updateSetting]);
  const [showHdMenu, setShowHdMenu] = useState(false);

  const updateResolution = useCallback(async (mode: 'Fast' | 'Standard' | 'High') => {
    setHdMode(mode);
    setShowHdMenu(false);
  }, [setHdMode]);

  const updateSetting = useCallback((key: any, value: boolean | ((prev: boolean) => boolean)) => {
    const newValue = typeof value === 'function' ? value(settings[key as keyof typeof settings] as boolean) : value;
    _updateSetting(key as any, newValue);
  }, [_updateSetting, settings]);

  // Helper: Get current frame brightness
  const getBrightness = useCallback((): number => {
    if (!videoRef.current || videoRef.current.readyState !== 4) return 255;
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 50;
      canvas.height = 50;
      const ctx = canvas.getContext('2d');
      if (!ctx) return 255;
      
      const vw = videoRef.current.videoWidth;
      const vh = videoRef.current.videoHeight;
      
      ctx.drawImage(videoRef.current, (vw - 50) / 2, (vh - 50) / 2, 50, 50, 0, 0, 50, 50);
      const data = ctx.getImageData(0, 0, 50, 50).data;
      let brightness = 0;
      for (let i = 0; i < data.length; i += 4) {
        brightness += (data[i] + data[i + 1] + data[i + 2]) / 3;
      }
      return brightness / (data.length / 4);
    } catch (e) {
      return 255;
    }
  }, []);

  // Phone camera native capture handler
  const toggleFlash = useCallback(async (forcedMode?: 'off' | 'auto' | 'torch') => {
    let nextMode: 'off' | 'auto' | 'torch' = 'off';
    if (forcedMode && (forcedMode === 'off' || forcedMode === 'auto' || forcedMode === 'torch')) {
      nextMode = forcedMode;
    } else {
      if (flashMode === 'off') nextMode = 'auto';
      else if (flashMode === 'auto') nextMode = 'torch';
      else nextMode = 'off';
    }
    setFlashMode(nextMode);
  }, [flashMode, setFlashMode]);

  // Capture handles
  const handlePhoneCameraFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || isCapturing) return;
    setIsCapturing(true);

    if (settings.clickSound) {
      playShutterSound();
    }

    const objectUrl = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const w = img.naturalWidth || img.width;
      const h = img.naturalHeight || img.height;
      const actualAspect = w > h ? h / w : w / h;
      const targetAspect = 3 / 4;

      const diff = Math.abs(actualAspect - targetAspect);
      const isNotThreeFour = diff > 0.05;

      onCapture(file, isBatchMode, {
        tl: { x: 0, y: 0 },
        tr: { x: 100, y: 0 },
        br: { x: 100, y: 100 },
        bl: { x: 0, y: 100 }
      }, settings.autoCrop || isNotThreeFour);

      if (isBatchMode) {
        setBatchCount(c => c + 1);
      }
      setIsCapturing(false);
      URL.revokeObjectURL(objectUrl);
      e.target.value = '';
    };
    img.onerror = () => {
      setIsCapturing(false);
      URL.revokeObjectURL(objectUrl);
    };
    img.src = objectUrl;
  };

  // Capture handles
  const captureFrame = useCallback(async () => {
    if (settings.usePhoneCamera) {
      phoneCameraInputRef.current?.click();
      return;
    }

    if (!videoRef.current || !canvasRef.current || isCapturing) return;

    setIsCapturing(true);

    try {
      // Viewfinder relative camera shutter flash block
      const parentContainer = videoRef.current?.parentElement || document.body;
      const isFloating = parentContainer === document.body;
      const flashScreen = document.createElement('div');
      flashScreen.className = `${isFloating ? 'fixed' : 'absolute'} inset-0 bg-white z-[9999] pointer-events-none transition-opacity duration-150`;
      parentContainer.appendChild(flashScreen);
      setTimeout(() => {
        flashScreen.style.opacity = '0';
        setTimeout(() => flashScreen.remove(), 165);
      }, 55);

      const { handleCapturedFrameOffThread } = await import('../utils/imageWorkerClient');

      if (settings.doubleFocusEnabled) {
        try {
          await applyFocus('continuous');
          await new Promise(resolve => setTimeout(resolve, 300));
          await applyFocus('single');
          await new Promise(resolve => setTimeout(resolve, 400));
        } catch (focusErr) {
          console.warn('Double focus sequence failed:', focusErr);
        }
      }

      if (settings.clickSound) {
        playShutterSound();
      }

      const track = videoTrack;
      if (!track) { setIsCapturing(false); return; }
      
      let imageCapture;
      try {
        imageCapture = new (window as any).ImageCapture(track);
      } catch (e) {
        console.warn("ImageCapture creation failed:", e);
        setIsCapturing(false);
        return;
      }
      let bitmap = await imageCapture.grabFrame();
      
      // Method B: Shrink Captured Canvas Frame Size for paper mode to save RAM and persist low pixel frames
      const activeMode = settings.scannerSubTab || 'paper';
      const isCardMode = activeMode === 'idcard' || activeMode === 'grid';
      if (!isCardMode) {
        let maxCaptureDim = 1920;
        if (settings.hdMode === 'Fast') {
          maxCaptureDim = 1280;
        } else if (settings.hdMode === 'Standard') {
          maxCaptureDim = 1920;
        } else if (settings.hdMode === 'High') {
          maxCaptureDim = 2560;
        }

        const currentW = bitmap.width;
        const currentH = bitmap.height;
        const currentMax = Math.max(currentW, currentH);

        if (currentMax > maxCaptureDim) {
          const scale = maxCaptureDim / currentMax;
          const targetW = Math.round(currentW * scale);
          const targetH = Math.round(currentH * scale);

          try {
            const shrinkCanvas = typeof OffscreenCanvas !== 'undefined'
              ? new OffscreenCanvas(targetW, targetH)
              : document.createElement('canvas');
            if (shrinkCanvas instanceof HTMLCanvasElement) {
              shrinkCanvas.width = targetW;
              shrinkCanvas.height = targetH;
            }
            const shrinkCtx = shrinkCanvas.getContext('2d');
            if (shrinkCtx) {
              shrinkCtx.drawImage(bitmap, 0, 0, targetW, targetH);
              bitmap.close();
              if (shrinkCanvas instanceof OffscreenCanvas) {
                bitmap = shrinkCanvas.transferToImageBitmap();
              } else {
                bitmap = await createImageBitmap(shrinkCanvas);
              }
            }
          } catch (resizeErr) {
            console.warn('Downscaling grabbed frame failed:', resizeErr);
          }
        }
      }
      
      // Check RAM (best effort)
      const isLowMemory = (navigator as any).deviceMemory ? (navigator as any).deviceMemory < 3 : false;
      
      const bitmapRatio = bitmap.width > bitmap.height 
        ? (bitmap.width / bitmap.height) 
        : (bitmap.height / bitmap.width);
      const isA4Supported = Math.abs(bitmapRatio - PAPER_RATIOS.A4_PORTRAIT) < 0.05;
      const paperTargetAspect = isA4Supported ? PAPER_RATIOS.A4 : PAPER_RATIOS.THREE_FOUR;

      let targetAspect = isCardMode ? CARD_RATIOS.LANDSCAPE : paperTargetAspect;
      const isPortraitView = window.innerHeight > window.innerWidth;
      
      if (isPortraitView && bitmap.width > bitmap.height) {
        try {
          const sw = bitmap.width;
          const sh = bitmap.height;
          const canvas = typeof OffscreenCanvas !== 'undefined'
            ? new OffscreenCanvas(sh, sw)
            : document.createElement('canvas');
          if (canvas instanceof HTMLCanvasElement) {
            canvas.width = sh;
            canvas.height = sw;
          }
          const ctx = canvas.getContext('2d') as any;
          if (ctx) {
            ctx.translate(sh, 0);
            ctx.rotate(90 * Math.PI / 180);
            ctx.drawImage(bitmap, 0, 0);
            bitmap.close();
            if (canvas instanceof OffscreenCanvas) {
              bitmap = canvas.transferToImageBitmap();
            } else {
              bitmap = await createImageBitmap(canvas);
            }
          }
          targetAspect = isCardMode ? CARD_RATIOS.LANDSCAPE : paperTargetAspect;
        } catch (err) {
          console.warn('Error auto-rotating captured frame to portrait:', err);
        }
      } else {
        targetAspect = isCardMode ? CARD_RATIOS.LANDSCAPE : paperTargetAspect;
      }

      const enhancements = {
        shadowRemove: settings.shadowRemoveEnabled,
        autoAdjust: settings.shadowRemoveEnabled
      };

      const blob = await handleCapturedFrameOffThread(bitmap, targetAspect, isLowMemory, enhancements);

      if (isBatchMode) setBatchCount(c => c + 1);
      const cardId = crypto.randomUUID();
      await saveImageBlob(cardId, blob); // disk pe original
      
      let cropPoints = getDefaultQuad(bitmap.width, bitmap.height, settings.scannerSubTab);
      let usedFallback = true;
      
      const needsDetection = Boolean(settings.autoDetectEnabled || settings.showGrid || settings.autoCrop);

      await savePageMeta(cardId, {
        cropPoints: cropPoints,
        rotate: 0,
        filter: 'original',
        adjustments: { b: 0, c: 0, s: 0 },
        scanMode: settings.scannerSubTab,
        usedFallback,
        timestamp: Date.now()
      });
      onCapture(blob, isBatchMode, cropPoints, undefined, needsDetection);
    } catch (err) {
      console.error('Capture failed:', err);
      // alert('Error during capture. Please check camera permissions or hardware.');
    } finally {
      setIsCapturing(false);
      if (settings.doubleFocusEnabled) {
        applyFocus('continuous').catch(() => {});
      }
    }
  }, [settings, isBatchMode, flashMode, isCapturing, onCapture, getBrightness, detectedCorners, applyFocus]);

  const streamRefExport = useRef(stream);
  streamRefExport.current = stream;
  const videoTrackRefExport = useRef(videoTrack);
  videoTrackRefExport.current = videoTrack;
  const supportsTorchRefExport = useRef(supportsTorch);
  supportsTorchRefExport.current = supportsTorch;

  return {
    videoRef,
    canvasRef,
    streamRef: streamRefExport,
    videoTrackRef: videoTrackRefExport,
    supportsTorchRef: supportsTorchRefExport,
    phoneCameraInputRef,
    settingsRef,
    isBatchMode,
    setIsBatchMode,
    batchCount,
    setBatchCount,
    flashMode,
    setFlashMode,
    showHdMenu,
    setShowHdMenu,
    hdMode,
    setHdMode,
    cameraError: cameraErrorVal,
    setCameraError: setCameraErrorLocal,
    restartCamera,
    isCapturing,
    isCameraReady,
    settings,
    updateSetting,
    updateResolution,
    toggleFlash,
    handlePhoneCameraFileChange,
    captureFrame,
    detectedCorners,
    setDetectedCorners,
  };
}
