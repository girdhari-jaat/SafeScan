import { useState, useCallback, useRef, useEffect } from 'react';
import { useCamera } from '../contexts/CameraContext';
import { addLog } from '../utils/renderStats';

export interface UnifiedScannerHookProps {
  onCapture?: (blob: Blob, autoCropped?: boolean) => void;
  onIdCardCapture?: (front: Blob, back: Blob) => void;
  settings: any;
  initialMode?: 'paper' | 'idcard' | 'grid';
  viewfinderRef?: React.RefObject<any>;
}

/**
 * UnifiedscannerHook
 * Manages the logic for different scanning modes (Paper, ID Card, Grid)
 * and orchestrates the capture sequence and feedback.
 */
// Web Audio API Shutter Click Synthesizer for reliable offline-first feedback
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
    console.warn("[playShutterSound] failed:", err);
  }
};

export function useUnifiedscannerHook({ 
  onCapture, 
  onIdCardCapture, 
  settings,
  initialMode = 'paper',
  viewfinderRef
}: UnifiedScannerHookProps) {
  const { isReady, detectedCorners, applyFocus } = useCamera();
  
  const [mode, setMode] = useState<'paper' | 'idcard' | 'grid'>(initialMode);
  const [isCapturing, setIsCapturing] = useState(false);
  const isCapturingRef = useRef(false);
  const [idStep, setIdStep] = useState<'front' | 'back' | null>(initialMode === 'idcard' ? 'front' : null);
  const [capturedFront, setCapturedFront] = useState<Blob | null>(null);
  const phoneCameraInputRef = useRef<HTMLInputElement>(null);

  // Auto-Capture Stability State
  const stabilityTimerRef = useRef<number>(0);
  const lastCornersRef = useRef<any>(null);
  const cooldownRef = useRef<boolean>(false);

  // Sound and Haptic Feedback
  const triggerFeedback = useCallback(() => {
    if (settings.clickSound) {
      playShutterSound();
    }
    if ('vibrate' in navigator) {
      try {
        navigator.vibrate(50);
      } catch (e) {}
    }
  }, [settings.clickSound]);

  const handlePhoneCameraFileChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || isCapturingRef.current) return;
    
    isCapturingRef.current = true;
    setIsCapturing(true);
    triggerFeedback();

    if (onCapture) {
      // Use false for wasAutoCropped to trigger manual crop tool for native camera imports
      onCapture(file, false);
    }
    
    setIsCapturing(false);
    isCapturingRef.current = false;
    e.target.value = '';
  }, [onCapture, triggerFeedback]);

  // Stability Helper
  const checkStability = (current: any[], last: any[]) => {
    if (!last || current.length !== last.length) return false;
    const threshold = 0.05; // 5% movement allowed
    for (let i = 0; i < current.length; i++) {
        const dx = Math.abs(current[i].x - last[i].x);
        const dy = Math.abs(current[i].y - last[i].y);
        if (dx > threshold || dy > threshold) return false;
    }
    return true;
  };

  /**
   * Main capture entry point
   * viewfinderRef should be the ref object from UnifiedViewfinder
   */
  const handleCapture = useCallback(async (viewfinderRef: any) => {
    addLog(`[UnifiedscannerHook] handleCapture triggered: Ready=${isReady}, Viewfinder=${!!viewfinderRef?.current}`);
    if (isCapturingRef.current || isCapturing) return;

    if (settings.usePhoneCamera) {
      phoneCameraInputRef.current?.click();
      return;
    }

    if (!isReady || !viewfinderRef?.current) {
      addLog("[UnifiedscannerHook] Capture aborted: Camera not ready or viewfinder ref missing.");
      return;
    }
    
    isCapturingRef.current = true;
    setIsCapturing(true);
    triggerFeedback();

    try {
      // Double Focus Sequence
      if (settings.doubleFocusEnabled) {
        addLog("Double Focus triggered");
        await applyFocus('continuous');
        await new Promise(resolve => setTimeout(resolve, 300));
        await applyFocus('single');
        await new Promise(resolve => setTimeout(resolve, 400));
      }

      // Delegate frame capture to the Viewfinder ref
      const blob = await viewfinderRef.current.captureFrame();
      if (!blob) {
        isCapturingRef.current = false;
        setIsCapturing(false);
        return;
      }

      let finalBlob = blob;
      let wasAutoCropped = false;

      // Note: We now defer autoCrop to the background detection inside AppHook.ts
      // to avoid blocking the UI thread and adhere to the non-destructive editing model.

      // Logic branch based on mode
      if (mode === 'idcard') {
        if (idStep === 'front') {
          setCapturedFront(finalBlob);
          setIdStep('back');
        } else {
          // Both sides captured
          if (onIdCardCapture && capturedFront) {
            onIdCardCapture(capturedFront, finalBlob);
          } else if (onCapture) {
            // Fallback if specific ID handler missing
            onCapture(finalBlob, wasAutoCropped);
          }
          // Reset sequence for next card
          setCapturedFront(null);
          setIdStep('front');
        }
      } else {
        // Paper or Grid mode: direct single capture
        if (onCapture) {
          onCapture(finalBlob, wasAutoCropped);
        }
      }
    } catch (err) {
      console.error("[UnifiedscannerHook] Capture error:", err);
    } finally {
      if (settings.doubleFocusEnabled) {
        applyFocus('continuous').catch(() => {});
      }
      isCapturingRef.current = false;
      setIsCapturing(false);
    }
  }, [isCapturing, isReady, mode, idStep, capturedFront, onCapture, onIdCardCapture, triggerFeedback, settings.autoCrop, detectedCorners, settings.doubleFocusEnabled, applyFocus]);

  // Stability-based Auto-Capture Loop
  useEffect(() => {
    if (isCapturing || !isReady || cooldownRef.current || settings.usePhoneCamera) {
        stabilityTimerRef.current = 0;
        return;
    }

    // Only auto-capture in paper or grid mode
    if (detectedCorners && detectedCorners.length === 4 && (mode === 'paper' || mode === 'grid')) {
        const isStable = checkStability(detectedCorners, lastCornersRef.current);
        if (isStable) {
            stabilityTimerRef.current += 100;
            // Optimized: Reduced stability duration requirement from 1.5s to 700ms for fast lock and high-speed responsiveness
            if (stabilityTimerRef.current >= 700) {
                addLog("Geometric: Position locked. Triggering focus sequence...");
                handleCapture(viewfinderRef);
                
                // Prevent recursive captures while ensuring the 2nd page captures instantly!
                // Optimized: Reduced cooldown from 3.5s to 1.2s so sequential scanning is rapid and smooth
                cooldownRef.current = true;
                setTimeout(() => { cooldownRef.current = false; }, 1200);
                stabilityTimerRef.current = 0;
            }
        } else {
            stabilityTimerRef.current = 0;
        }
        lastCornersRef.current = detectedCorners;
    } else {
        stabilityTimerRef.current = 0;
    }
  }, [detectedCorners, isCapturing, isReady, settings.usePhoneCamera, mode, handleCapture, viewfinderRef]);

  // Mode switching logic (resets internal sequence state)
  const changeMode = useCallback((newMode: 'paper' | 'idcard' | 'grid') => {
    setMode(newMode);
    setIdStep(newMode === 'idcard' ? 'front' : null);
    setCapturedFront(null);
  }, []);

  // UI Helper: Label for the active scanning slot
  const activeSlotLabel = mode === 'idcard' 
    ? (idStep === 'front' ? 'Front Side' : 'Back Side')
    : mode === 'grid' ? 'Grid Area' : 'Document Area';

  return {
    mode,
    changeMode,
    isCapturing,
    handleCapture,
    idStep,
    activeSlotLabel,
    capturedFront,
    phoneCameraInputRef,
    handlePhoneCameraFileChange,
  };
}
