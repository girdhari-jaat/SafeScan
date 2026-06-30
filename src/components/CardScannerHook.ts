import { useState, useEffect, useCallback, useRef } from 'react';
import { PageCorners, ScanFilterType, PageAdjustments } from '../types';
import { useSharedSettings } from '../lib/useSharedSettings';
import { useCamera } from '../contexts/CameraContext';
import { CARD_RATIOS } from '../constants';
import { saveImageBlob, getImageBlob, deleteImageBlob } from '../utils/db';

// Web Audio API Shutter Click Synthesizer
const playShutterSound = () => {
  try {
    const AudioContext = (window as any).AudioContext || (window as any).webkitAudioContext;
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
  } catch (err) {}
};

interface CardSlot {
  id: string;
  name: string;
  corners: PageCorners;
}

export type CardScannerMode = 'idcard' | 'grid';

export const QUALITY_PRESETS: Record<CardScannerMode, Record<'Fast' | 'Standard' | 'High', { width: number, height: number }>> = {
  idcard: {
    Fast: { width: 1654, height: Math.round(1654 / CARD_RATIOS.LANDSCAPE) },
    Standard: { width: 2206, height: Math.round(2206 / CARD_RATIOS.LANDSCAPE) },
    High: { width: 3306, height: Math.round(3306 / CARD_RATIOS.LANDSCAPE) }
  },
  grid: {
    Fast: { width: 1654, height: Math.round(1654 / CARD_RATIOS.LANDSCAPE) },
    Standard: { width: 2206, height: Math.round(2206 / CARD_RATIOS.LANDSCAPE) },
    High: { width: 3306, height: Math.round(3306 / CARD_RATIOS.LANDSCAPE) }
  }
};

const CNIC_SLOTS: CardSlot[] = [
  { id: 'front', name: 'Front Side', corners: { tl: { x: 10, y: 15 }, tr: { x: 90, y: 15 }, br: { x: 90, y: 45 }, bl: { x: 10, y: 45 } } },
  { id: 'back', name: 'Back Side', corners: { tl: { x: 10, y: 55 }, tr: { x: 90, y: 55 }, br: { x: 90, y: 85 }, bl: { x: 10, y: 85 } } },
];

const GRID_SLOTS: CardSlot[] = [
  { id: '1', name: 'Slot 1', corners: { tl: { x: 5, y: 5 }, tr: { x: 45, y: 5 }, br: { x: 45, y: 22 }, bl: { x: 5, y: 22 } } },
  { id: '2', name: 'Slot 2', corners: { tl: { x: 55, y: 5 }, tr: { x: 95, y: 5 }, br: { x: 95, y: 22 }, bl: { x: 55, y: 22 } } },
  { id: '3', name: 'Slot 3', corners: { tl: { x: 5, y: 25 }, tr: { x: 45, y: 25 }, br: { x: 45, y: 42 }, bl: { x: 5, y: 42 } } },
  { id: '4', name: 'Slot 4', corners: { tl: { x: 55, y: 25 }, tr: { x: 95, y: 25 }, br: { x: 95, y: 42 }, bl: { x: 55, y: 42 } } },
  { id: '5', name: 'Slot 5', corners: { tl: { x: 5, y: 45 }, tr: { x: 45, y: 45 }, br: { x: 45, y: 62 }, bl: { x: 5, y: 62 } } },
  { id: '6', name: 'Slot 6', corners: { tl: { x: 55, y: 45 }, tr: { x: 95, y: 45 }, br: { x: 95, y: 62 }, bl: { x: 55, y: 62 } } },
  { id: '7', name: 'Slot 7', corners: { tl: { x: 5, y: 65 }, tr: { x: 45, y: 65 }, br: { x: 45, y: 82 }, bl: { x: 5, y: 82 } } },
  { id: '8', name: 'Slot 8', corners: { tl: { x: 55, y: 65 }, tr: { x: 95, y: 65 }, br: { x: 95, y: 82 }, bl: { x: 55, y: 82 } } },
];

export interface CardData {
  imageId: string;
  corners: PageCorners;
  filter: ScanFilterType;
  adjustments: PageAdjustments;
  rotation: number;
  previewUrl?: ImageBitmap | string;
}

interface UseCardScannerProps {
  mode: CardScannerMode;
  initialPages?: any[];
  onAutosave?: (cards: (CardData | null)[]) => Promise<void>;
  onSaveSession?: (cards: (CardData | null)[]) => Promise<void>;
  isSlotsVisible?: boolean;
}

export type CardSlotStatus = 'empty' | 'capturing' | 'filled';

export function useCardScannerHook({ mode, initialPages: _initialPages, onAutosave: _onAutosave, onSaveSession, isSlotsVisible = false }: UseCardScannerProps) {
  const { settings, updateSetting: _updateSetting } = useSharedSettings();
  const { 
    stream: cameraStream, 
    videoTrack, 
    cameraError, 
    isReady: cameraIsReady, 
    supportsTorch, 
    startCamera, 
    stopCamera, 
    restartCamera, 
    videoRef, 
    canvasRef,
    detectedCorners,
    setDetectedCorners,
    applyFocus 
  } = useCamera();

  const SLOTS = mode === 'idcard' ? CNIC_SLOTS : GRID_SLOTS;
  const slotCount = SLOTS.length;

  const isLowMemoryDevice = typeof navigator !== 'undefined' && (navigator as any).deviceMemory ? (navigator as any).deviceMemory < 3 : false;
  const isLowMemory = isLowMemoryDevice || settings.hdMode === 'Fast';
  const [slotIndex, setSlotIndex] = useState(0);
  const [filledSlots, setFilledSlots] = useState<CardSlotStatus[]>(new Array(slotCount).fill('empty'));
  const [isCapturing, setIsCapturing] = useState(false);
  const isCameraReady = cameraIsReady;
  const cameraAccessError = cameraError;
  const setCameraAccessError = (_val: boolean) => {}; // No-op, we leverage context cameraError
  const [pdfReady, setPdfReady] = useState(false);

  const flashMode = settings.flashMode;
  const hdMode = settings.hdMode;
  const setFlashMode = useCallback((val: 'off' | 'auto' | 'torch') => _updateSetting('flashMode', val), [_updateSetting]);
  const setHdMode = useCallback((val: 'Fast' | 'Standard' | 'High') => _updateSetting('hdMode', val), [_updateSetting]);
  const updateSetting = useCallback((key: any, value: any) => {
    const newValue = typeof value === 'function' ? value(settings[key as keyof typeof settings]) : value;
    _updateSetting(key, newValue);
  }, [_updateSetting, settings]);

  const [currentTab, setCurrentTab] = useState<CardScannerMode>(mode);
  const [cropCardIndex, setCropCardIndex] = useState<number | null>(null);
  const cameraAspectRatio = 4 / 3;

  const cardsRef = useRef<(CardData | null)[]>(new Array(slotCount).fill(null));
  const buttonsRowRef = useRef<HTMLDivElement>(null);
  const gridSlotsRef = useRef<(HTMLDivElement | null)[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const isDarkRef = useRef(false);

  // Sync filledSlots state with cardsRef, and handle mode changes
  useEffect(() => {
    if (cardsRef.current.length !== slotCount) {
      // Clear all out to prevent overflow or bugs
      setSlotIndex(0);
      setPdfReady(false);
      cardsRef.current = new Array(slotCount).fill(null);
      setFilledSlots(new Array(slotCount).fill('empty' as CardSlotStatus));
      setCurrentTab(mode);
    } else {
      const filled = cardsRef.current.map(c => c ? 'filled' as CardSlotStatus : 'empty' as CardSlotStatus);
      setFilledSlots(filled);
      setPdfReady(filled.some(f => f === 'filled'));
    }
  }, [mode, slotCount]);

  // Clean up memory on unmount
  useEffect(() => {
    return () => {
      cardsRef.current.forEach(card => {
        if (card && card.previewUrl && typeof card.previewUrl !== 'string') {
          try {
            (card.previewUrl as ImageBitmap).close();
          } catch (e) {}
        }
      });
    };
  }, []);

  const isViewfinderActive = !isSlotsVisible && cropCardIndex === null;

  // Handle visibility change, initial mount, and active viewfinder state
  useEffect(() => {
    let active = true;
    const usePhoneCamera = !!settings?.usePhoneCamera;
    
    // We let App level lifecycle handle camera starts mostly, but we can ensure it here
    if (isViewfinderActive && !usePhoneCamera) {
      startCamera();
    }

    const handleVisibilityChange = () => {
      if (!document.hidden && active && isViewfinderActive && !usePhoneCamera) {
        startCamera();
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      active = false;
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [startCamera, isViewfinderActive, settings?.usePhoneCamera]);

  const persistSession = useCallback(async () => {
    const sessionKey = `card_session_${mode}`;
    const data = cardsRef.current.map((c, i) => c ? { ...c, slotIndex: i } : null).filter(Boolean);
    localStorage.setItem(sessionKey, JSON.stringify(data));
    if (onSaveSession) await onSaveSession(cardsRef.current);
  }, [mode, onSaveSession]);

  const restoreSession = useCallback(async () => {
    const sessionKey = `card_session_${mode}`;
    const saved = localStorage.getItem(sessionKey);
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        const newCards = new Array(slotCount).fill(null);
        const newFilled = new Array(slotCount).fill('empty' as CardSlotStatus);
        
        const { warpPreview } = await import('../utils/imageWorkerClient');

        for (const item of parsed) {
          if (item && typeof item.slotIndex === 'number' && item.slotIndex < slotCount) {
             const blob = await getImageBlob(item.imageId); if (blob) { item.previewUrl = URL.createObjectURL(blob); newCards[item.slotIndex] = item; newFilled[item.slotIndex] = 'filled' as CardSlotStatus; continue; }
             if (blob) {
               try {
                 const pageMeta = {
                   cropPoints: item.corners,
                   rotate: item.rotation,
                   filter: item.filter,
                   adjustments: {
                     b: item.adjustments.brightness,
                     c: item.adjustments.contrast,
                     s: item.adjustments.saturation
                   }
                 };

                 const temp = await createImageBitmap(blob);
                  const w = temp.width;
                  const h = temp.height;

                  const maxDim = 800;
                  let pBitmap: ImageBitmap;
                  if (w > h) {
                    pBitmap = await createImageBitmap(temp, {
                      resizeWidth: maxDim,
                      resizeHeight: Math.round((h * maxDim) / w),
                      resizeQuality: isLowMemory ? 'low' : 'medium'
                    });
                  } else {
                    pBitmap = await createImageBitmap(temp, {
                      resizeHeight: maxDim,
                      resizeWidth: Math.round((w * maxDim) / h),
                      resizeQuality: isLowMemory ? 'low' : 'medium'
                    });
                  }
                  temp.close();

                 const warped = await warpPreview(pBitmap, {
                   ...pageMeta,
                   scanMode: mode === 'idcard' ? 'card' : 'grid'
                 });
                 item.previewUrl = warped;
               } catch (err) {
                 console.error(`Error recreating preview for slot ${item.slotIndex}:`, err);
               }
             }

            newCards[item.slotIndex] = item;
            newFilled[item.slotIndex] = 'filled' as CardSlotStatus;
          }
        }
        
        cardsRef.current = newCards;
        setFilledSlots(newFilled);
        setPdfReady(newFilled.some(f => f === 'filled'));
        
        // Find next empty slot
        const nextIdx = newFilled.findIndex(f => f === 'empty');
        if (nextIdx !== -1) setSlotIndex(nextIdx);
        else setSlotIndex(slotCount - 1);
        
      } catch (e) {
        console.error("Failed to restore session:", e);
      }
    }
  }, [mode, slotCount]);

  const deleteSlot = useCallback(async (index: number) => {
    const card = cardsRef.current[index];
    if (card) {
      if (card.previewUrl && typeof card.previewUrl !== 'string') {
        try {
          (card.previewUrl as ImageBitmap).close();
        } catch (e) {}
      }
      if (card.imageId) {
        try {
          await deleteImageBlob(card.imageId);
        } catch (e) {}
      }
    }
    
    cardsRef.current[index] = null;
    setFilledSlots(prev => {
      const next = [...prev];
      next[index] = 'empty' as CardSlotStatus;
      return next;
    });
    setSlotIndex(index);
    setPdfReady(cardsRef.current.some(c => !!c));
    await persistSession();
  }, [persistSession]);

  const clearAllSlots = useCallback(async () => {
    cardsRef.current.forEach(card => {
      if (card && card.previewUrl && typeof card.previewUrl !== 'string') {
        try {
          (card.previewUrl as ImageBitmap).close();
        } catch (e) {}
      }
    });

    cardsRef.current = new Array(slotCount).fill(null);
    setFilledSlots(new Array(slotCount).fill('empty' as CardSlotStatus));
    setSlotIndex(0);
    setPdfReady(false);
    await persistSession();
  }, [slotCount, persistSession]);

  // Live Flashlight / Torch control
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
  const updateResolution = useCallback(async (res: string) => {
    setHdMode(res as any);
    updateSetting('hdMode', res);
  }, [updateSetting, setHdMode]);

  const initiateRetake = useCallback(() => {
    if (cardsRef.current[slotIndex]) {
      const card = cardsRef.current[slotIndex]!;
      if (card.previewUrl && typeof card.previewUrl !== 'string') {
        try {
          (card.previewUrl as ImageBitmap).close();
        } catch (e) {}
      }
      if (card.imageId) {
        deleteImageBlob(card.imageId).catch(() => {});
      }
      cardsRef.current[slotIndex] = null;
      setFilledSlots(prev => {
        const next = [...prev];
        next[slotIndex] = 'empty';
        return next;
      });
      persistSession();
    } else if (slotIndex > 0) {
      setSlotIndex(slotIndex - 1);
      if (cardsRef.current[slotIndex - 1]) {
        const card = cardsRef.current[slotIndex - 1]!;
        if (card.previewUrl && typeof card.previewUrl !== 'string') {
          try {
            (card.previewUrl as ImageBitmap).close();
          } catch (e) {}
        }
        if (card.imageId) {
          deleteImageBlob(card.imageId).catch(() => {});
        }
        cardsRef.current[slotIndex - 1] = null;
        setFilledSlots(prev => {
          const next = [...prev];
          next[slotIndex - 1] = 'empty';
          return next;
        });
        persistSession();
      }
    }
  }, [slotIndex, persistSession]);

  const captureFrame = useCallback(async () => {
    if (isCapturing) return;

    if (settings.usePhoneCamera) {
      fileInputRef.current?.click();
      return;
    }

    if (!videoRef.current) return;
    if (slotIndex >= slotCount) return;

    // Capture Flash Effect
    const parentContainer = videoRef.current?.parentElement || document.body;
    const flashScreen = document.createElement('div');
    flashScreen.className = 'absolute inset-0 bg-white z-[9999] pointer-events-none transition-opacity duration-150 rounded-xl';
    parentContainer.appendChild(flashScreen);
    setTimeout(() => {
      flashScreen.style.opacity = '0';
      setTimeout(() => flashScreen.remove(), 165);
    }, 55);

    if (settings.clickSound) {
      playShutterSound();
    }

    setIsCapturing(true);
    setFilledSlots(prev => {
      const next = [...prev];
      next[slotIndex] = 'capturing';
      return next;
    });

    try {
      const video = videoRef.current;
      if (!video) throw new Error('Video source not connected.');

    // Ensure video is playing and attached if stream exists
    if (cameraStream && (video.paused || video.srcObject !== cameraStream)) {
      video.srcObject = cameraStream;
      await video.play().catch(e => {
        throw new Error('Failed to play video stream: ' + e.message);
      });
    }

    // Wait for video dimensions to be available with a promise-based retry
    if (video.videoWidth === 0 || video.readyState < 2) {
      let retryCount = 0;
      while ((video.videoWidth === 0 || video.readyState < 2) && retryCount < 30) {
        await new Promise(resolve => setTimeout(resolve, 100));
        retryCount++;
      }
    }

    const width = video.videoWidth;
    const height = video.videoHeight;
    if (width === 0 || height === 0) {
      throw new Error('Video stream not ready. Check camera permissions or try restarting the scanner.');
    }

      const preset = QUALITY_PRESETS[mode]?.[hdMode as 'Fast' | 'Standard' | 'High'] || { width: 1654, height: 1044 };
      const { handleCapturedFrameOffThread } = await import('../utils/imageWorkerClient');
      
      const enhancements = {
        shadowRemove: settings.shadowRemoveEnabled,
        autoAdjust: settings.shadowRemoveEnabled
      };

      let maxCardDim = 2206; // Default to Standard
      if (hdMode === 'Fast') {
        maxCardDim = 1654;
      } else if (hdMode === 'Standard') {
        maxCardDim = 2206;
      } else if (hdMode === 'High') {
        maxCardDim = 3306;
      }

      const currentMax = Math.max(width, height);
      let targetW = width;
      let targetH = height;

      if (currentMax > maxCardDim) {
        const scale = maxCardDim / currentMax;
        targetW = Math.round(width * scale);
        targetH = Math.round(height * scale);
      }

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

      let bitmap: ImageBitmap;
      try {
        if (currentMax > maxCardDim) {
          bitmap = await (window as any).createImageBitmap(video, {
            resizeWidth: targetW,
            resizeHeight: targetH,
            resizeQuality: isLowMemory ? 'low' : 'medium'
          });
        } else {
          bitmap = await (window as any).createImageBitmap(video);
        }
      } catch (nativeErr) {
        console.warn('Native createImageBitmap resize failed, using fallback:', nativeErr);
        let rawBitmap = await (window as any).createImageBitmap(video);
        if (currentMax > maxCardDim) {
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
              shrinkCtx.drawImage(rawBitmap, 0, 0, targetW, targetH);
              rawBitmap.close();
              if (shrinkCanvas instanceof OffscreenCanvas) {
                rawBitmap = shrinkCanvas.transferToImageBitmap();
              } else {
                rawBitmap = await createImageBitmap(shrinkCanvas);
              }
            }
          } catch (resizeErr) {
            console.warn('Downscaling grabbed card frame fallback failed:', resizeErr);
          }
        }
        bitmap = rawBitmap;
      }

      const blob = await handleCapturedFrameOffThread(bitmap, preset.width / preset.height, isLowMemory, enhancements);
      
      if (!blob) throw new Error('Failed to create blob from captured frame');
      
      const imageId = `img_${crypto.randomUUID()}`;
      await saveImageBlob(imageId, blob);

      if (cardsRef.current[slotIndex]) {
        const oldCard = cardsRef.current[slotIndex]!;
        if (oldCard.previewUrl && typeof oldCard.previewUrl !== 'string') {
          try { (oldCard.previewUrl as ImageBitmap).close(); } catch(e) {}
        }
        if (oldCard.imageId) {
          deleteImageBlob(oldCard.imageId).catch(() => {});
        }
      }

      // Respect autoCrop setting during capture for ID/Grid cards
      let initialCorners: PageCorners = { tl: { x: 5, y: 5 }, tr: { x: 95, y: 5 }, br: { x: 95, y: 95 }, bl: { x: 5, y: 95 } };
      if (settings.autoCrop) {
        try {
          const { detectCornersOffThread } = await import('../utils/imageWorkerClient');
          const tempBmp = await createImageBitmap(blob);
          const detected = await detectCornersOffThread(tempBmp, mode === 'idcard' ? 'card' : 'grid', !settings.autoDetectEnabled);
          if (detected && detected.corners) {
            const w = detected.originalWidth;
            const h = detected.originalHeight;
            initialCorners = {
              tl: { x: (detected.corners[0].x / w) * 100, y: (detected.corners[0].y / h) * 100 },
              tr: { x: (detected.corners[1].x / w) * 100, y: (detected.corners[1].y / h) * 100 },
              br: { x: (detected.corners[2].x / w) * 100, y: (detected.corners[2].y / h) * 100 },
              bl: { x: (detected.corners[3].x / w) * 100, y: (detected.corners[3].y / h) * 100 },
            };
          }
        } catch (err) {
          console.warn('Auto corner detection failed on capture:', err);
        }
      }

      const newCard: CardData = {
        imageId,
        corners: initialCorners,
        filter: 'original',
        adjustments: { brightness: 0, contrast: 0, saturation: 0, sharpness: 0, shadows: 0, temperature: 0 },
        rotation: 0,
        previewUrl: ''
      };

      // Generate preview for slot immediately
      const { warpPreview } = await import('../utils/imageWorkerClient');
      try {
        const tempBmp = await createImageBitmap(blob);
        const w = tempBmp.width;
        const h = tempBmp.height;

        const maxDim = 800;
        let pBitmap: ImageBitmap;
        if (w > h) {
          pBitmap = await createImageBitmap(tempBmp, {
            resizeWidth: maxDim,
            resizeHeight: Math.round((h * maxDim) / w),
            resizeQuality: isLowMemory ? 'low' : 'medium'
          });
        } else {
          pBitmap = await createImageBitmap(tempBmp, {
            resizeHeight: maxDim,
            resizeWidth: Math.round((w * maxDim) / h),
            resizeQuality: isLowMemory ? 'low' : 'medium'
          });
        }
        tempBmp.close();

        const warped = await warpPreview(pBitmap, {
          cropPoints: newCard.corners,
          rotate: newCard.rotation,
          filter: newCard.filter,
          adjustments: { b: 0, c: 0, s: 0 },
          scanMode: mode === 'idcard' ? 'card' : 'grid'
        });
        newCard.previewUrl = warped;
      } catch (previewErr) {
        console.warn('Failed to generate initial preview bitmap:', previewErr);
      }

      cardsRef.current[slotIndex] = newCard;
      setFilledSlots(prev => {
        const next = [...prev];
        next[slotIndex] = 'filled';
        return next;
      });
      setPdfReady(true);

      const nextIdx = cardsRef.current.findIndex(c => !c);
      if (nextIdx !== -1) {
        setSlotIndex(nextIdx);
      } else {
        setSlotIndex(slotCount);
      }
      
      await persistSession();
    } catch (err) {
      console.error('Capture failed:', err);
      setFilledSlots(prev => {
        const next = [...prev];
        next[slotIndex] = 'empty';
        return next;
      });
    } finally {
      setIsCapturing(false);
      if (settings.doubleFocusEnabled) {
        applyFocus('continuous').catch(() => {});
      }
    }
  }, [slotIndex, slotCount, persistSession, isCapturing, mode, settings.doubleFocusEnabled, applyFocus]);

  const handleSlotClick = useCallback((index: number) => {
    if (filledSlots[index]) {
      setCropCardIndex(index);
    } else {
      setSlotIndex(index);
    }
  }, [filledSlots]);

  const uploadImage = useCallback(async (index: number, file: File) => {
    try {
      if (settings.clickSound) {
        playShutterSound();
      }
      if (cardsRef.current[index]) {
        const oldCard = cardsRef.current[index]!;
        if (oldCard.previewUrl && typeof oldCard.previewUrl !== 'string') {
          try { (oldCard.previewUrl as ImageBitmap).close(); } catch(e) {}
        }
        if (oldCard.imageId) {
          deleteImageBlob(oldCard.imageId).catch(() => {});
        }
      }

      const blob = new Blob([file], { type: file.type || 'image/jpeg' });
      const imageId = `img_${crypto.randomUUID()}`;
      await saveImageBlob(imageId, blob);

      let initialCorners = { tl: { x: 5, y: 5 }, tr: { x: 95, y: 5 }, br: { x: 95, y: 95 }, bl: { x: 5, y: 95 } };

      const { warpPreview, detectCornersOffThread } = await import('../utils/imageWorkerClient');

      // Attempt dynamic corner detection on upload exactly like CamScanner & Adobe Scan
      try {
        const tempBmp = await createImageBitmap(blob);
        const detected = await detectCornersOffThread(tempBmp, mode === 'idcard' ? 'card' : 'grid');
        if (detected && detected.corners) {
          const w = detected.originalWidth;
          const h = detected.originalHeight;
          initialCorners = {
            tl: { x: (detected.corners[0].x / w) * 100, y: (detected.corners[0].y / h) * 100 },
            tr: { x: (detected.corners[1].x / w) * 100, y: (detected.corners[1].y / h) * 100 },
            br: { x: (detected.corners[2].x / w) * 100, y: (detected.corners[2].y / h) * 100 },
            bl: { x: (detected.corners[3].x / w) * 100, y: (detected.corners[3].y / h) * 100 },
          };
        }
        tempBmp.close();
      } catch (err) {
        console.warn('Auto corner detection failed on upload:', err);
      }

      const newCard: CardData = {
        imageId,
        corners: initialCorners,
        filter: 'original',
        adjustments: { brightness: 0, contrast: 0, saturation: 0, sharpness: 0, shadows: 0, temperature: 0 },
        rotation: 0,
        previewUrl: ''
      };

      // Generate preview for slot immediately
      try {
        if (!settings.autoCrop) {
          // If autoCrop is disabled, provide raw image preview URL directly and don't throw scary logs
          newCard.previewUrl = URL.createObjectURL(blob);
        } else {
          const tempBmp = await createImageBitmap(blob);
          const w = tempBmp.width;
          const h = tempBmp.height;

          const maxDim = 800;
          let pBitmap: ImageBitmap;
          if (w > h) {
            pBitmap = await createImageBitmap(tempBmp, {
              resizeWidth: maxDim,
              resizeHeight: Math.round((h * maxDim) / w),
              resizeQuality: isLowMemory ? 'low' : 'medium'
            });
          } else {
            pBitmap = await createImageBitmap(tempBmp, {
              resizeHeight: maxDim,
              resizeWidth: Math.round((w * maxDim) / h),
              resizeQuality: isLowMemory ? 'low' : 'medium'
            });
          }
          tempBmp.close();

          const warped = await warpPreview(pBitmap, {
            cropPoints: newCard.corners,
            rotate: newCard.rotation,
            filter: newCard.filter,
            adjustments: { b: 0, c: 0, s: 0 },
            scanMode: mode === 'idcard' ? 'card' : 'grid'
          });
          newCard.previewUrl = warped;
        }
      } catch (previewErr) {
        console.warn('Failed to generate initial preview bitmap during upload:', previewErr);
        // Fallback to raw blob URL so user always gets a visible card preview
        if (!newCard.previewUrl) {
          newCard.previewUrl = URL.createObjectURL(blob);
        }
      }

      cardsRef.current[index] = newCard;
      const newFilled = cardsRef.current.map(c => c ? 'filled' as CardSlotStatus : 'empty' as CardSlotStatus);
      setFilledSlots(newFilled);
      setPdfReady(true);

      if (index === slotIndex) {
        const nextIdx = newFilled.findIndex(f => f === 'empty');
        if (nextIdx !== -1) {
          setSlotIndex(nextIdx);
        } else {
          setSlotIndex(slotCount - 1);
        }
      }

      await persistSession();
    } catch (e) {
      console.error("Upload failed in hook:", e);
    }
  }, [mode, slotIndex, slotCount, persistSession]);

  const executeExport = useCallback(async (title: string, action: 'save' | 'share' | 'print') => {
    try {
      const { generatePDFFromCards } = await import('../utils/pdfExport');
      const activeCards = cardsRef.current;
      if (activeCards.every(c => !c)) {
        alert('باکسز خالی ہیں، پہلے تصویریں لیں۔');
        return false;
      }

      const formatDateTime = () => {
        const now = new Date();
        return `${now.toLocaleDateString()} ${now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}`;
      };
      const docTitle = title || (mode === 'idcard' ? `CNIC ${formatDateTime()}` : `Grid ${formatDateTime()}`);
      await generatePDFFromCards(activeCards, docTitle, action as any, mode);
      
      // Policy rule: zero copy, clear RAM and DB after export
      setTimeout(async () => {
        await clearAllSlots();
        // Remove DB items
        for (const card of activeCards) {
          if (card && card.imageId) {
            try { await deleteImageBlob(card.imageId); } catch (e) {}
          }
        }
      }, 500);
      return true;
    } catch (e) {
      console.error("Export failed:", e);
      return false;
    }
  }, [mode, clearAllSlots]);

  const cameraStreamRefExport = useRef(cameraStream);
  cameraStreamRefExport.current = cameraStream;
  const videoTrackRefExport = useRef(videoTrack);
  videoTrackRefExport.current = videoTrack;

  return {
    slotIndex,
    setSlotIndex,
    filledSlots,
    pdfReady,
    cardsRef,
    videoRef,
    canvasRef,
    cameraStreamRef: cameraStreamRefExport,
    videoTrackRef: videoTrackRefExport,
    buttonsRowRef,
    gridSlotsRef,
    fileInputRef,
    isCapturing,
    isCameraReady,
    cameraAccessError,
    setCameraAccessError,
    startCamera,
    restartCamera,
    captureFrame,
    uploadImage,
    initiateRetake,
    executeExport,
    handleSlotClick,
    settings,
    updateSetting,
    flashMode,
    setFlashMode,
    hdMode,
    updateResolution,
    toggleFlash,
    currentTab,
    setCurrentTab,
    cropCardIndex,
    setCropCardIndex,
    persistSession,
    restoreSession,
    deleteSlot,
    clearAllSlots,
    stopAllTracks: stopCamera,
    detectedCorners,
    setDetectedCorners,
    isDarkRef,
    supportsTorchRef: { current: supportsTorch },
    SLOTS,
    cameraAspectRatio
  };
}
