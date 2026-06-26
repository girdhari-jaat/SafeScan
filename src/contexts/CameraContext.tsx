import { createContext, useContext, useEffect, useRef, useState, ReactNode, useCallback } from 'react';
import { useSharedSettings } from '../lib/useSharedSettings';
import { addLog } from '../utils/renderStats';

interface CameraContextType {
  stream: MediaStream | null;
  videoTrack: MediaStreamTrack | null;
  cameraError: boolean;
  isReady: boolean;
  supportsTorch: boolean;
  startCamera: () => Promise<void>;
  stopCamera: () => void;
  restartCamera: () => Promise<void>;
  applyTorch: (on: boolean) => Promise<void>;
  applyFocus: (mode: 'single' | 'continuous') => Promise<void>;
  videoRef: React.RefObject<HTMLVideoElement | null>;
  canvasRef: React.RefObject<HTMLCanvasElement | null>;
  captureFrame: (options: { targetWidth: number; aspectRatio: number }) => Promise<Blob | null>;
  detectedCorners: any;
  setDetectedCorners: (val: any) => void;
}

const CameraContext = createContext<CameraContextType | undefined>(undefined);

// Helper to ensure HTMLVideoElement is playing and has enough hardware buffering + actual frame dimensions
const ensureVideoReady = (video: HTMLVideoElement): Promise<boolean> => {
  if (!video) return Promise.resolve(false);
  
  // Attempt to trigger playback if video says paused
  if (video.paused) {
    video.play().catch(e => console.warn("[ensureVideoReady] Deferred play trigger:", e));
  }

  // ReadyState has actual video frames ready
  if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
    return Promise.resolve(true);
  }

  return new Promise((resolve) => {
    let resolved = false;
    const timeoutId = setTimeout(() => {
      if (!resolved) {
        resolved = true;
        resolve(video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0);
      }
    }, 2500); // 2.5 seconds cutoff for slow platforms

    const checkState = () => {
      if (resolved) return;
      if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
        resolved = true;
        clearTimeout(timeoutId);
        resolve(true);
      }
    };

    video.addEventListener('loadedmetadata', checkState);
    video.addEventListener('canplay', checkState);
    video.addEventListener('playing', checkState);

    // Complement events with animation frame polling fallback
    let rafId: number;
    const pollCheck = () => {
      if (resolved) return;
      if (video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0) {
        resolved = true;
        clearTimeout(timeoutId);
        resolve(true);
      } else {
        rafId = requestAnimationFrame(pollCheck);
      }
    };
    rafId = requestAnimationFrame(pollCheck);

    const cleanup = () => {
      video.removeEventListener('loadedmetadata', checkState);
      video.removeEventListener('canplay', checkState);
      video.removeEventListener('playing', checkState);
      cancelAnimationFrame(rafId);
    };

    const originalResolve = resolve;
    resolve = (status) => {
      cleanup();
      originalResolve(status);
    };
  });
};

export function CameraProvider({ children }: { children: ReactNode }) {
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [videoTrack, setVideoTrack] = useState<MediaStreamTrack | null>(null);
  const [cameraError, setCameraError] = useState(false);
  const [isReady, setIsReady] = useState(false);
  const [supportsTorch, setSupportsTorch] = useState(false);
  const [detectedCorners, setDetectedCorners] = useState<any>(null);
  
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  
  const streamRef = useRef<MediaStream | null>(null);
  const shouldBeRunningRef = useRef<boolean>(false);
  const startingRef = useRef<boolean>(false);
  const startCounterRef = useRef<number>(0);
  const restartCounterRef = useRef<number>(0);
  const { settings } = useSharedSettings();
  const isDarkRef = useRef(false);

  // Real-time custom document edge detection loop
  useEffect(() => {
    if (!settings.autoDetectEnabled || !isReady || cameraError) {
      setDetectedCorners(null);
      return;
    }

    let animationFrameId: number;
    let lastRan = 0;
    let workerAPI: any = null;

    import('../utils/imageWorkerClient').then(mod => {
      workerAPI = mod;
    });

    const loop = (timestamp: number) => {
      const video = videoRef.current;
      if (video && video.readyState === 4 && workerAPI && shouldBeRunningRef.current) {
        if (timestamp - lastRan >= 350) {
          lastRan = timestamp;
          const dw = 400;
          const dh = Math.round(400 * (video.videoHeight / video.videoWidth));
          
          if (!(window as any)._isDetectingCornersGlobal) {
            (window as any)._isDetectingCornersGlobal = true;
            createImageBitmap(video, { resizeWidth: dw, resizeHeight: dh, resizeQuality: 'low' })
              .then(async (bitmap) => {
                try {
                  const mode = settings.scannerSubTab === 'paper' ? 'paper' : (settings.scannerSubTab === 'idcard' ? 'card' : 'grid');
                  const result = await workerAPI.detectCornersOffThread(bitmap, mode, true);
                  const isDetected = !!(result && result.corners && result.corners.length === 4);
                  
                  if (isDetected !== (window as any)._lastWasDetected) {
                    (window as any)._lastWasDetected = isDetected;
                    if (isDetected) {
                      addLog(`Geometric: ${mode.toUpperCase()} edges locked`);
                    } else {
                      addLog(`Geometric: Searching for ${mode} boundary...`);
                    }
                  }

                  if (isDetected) {
                    const corners = result.corners!;
                    const finalW = result.originalWidth || dw;
                    const finalH = result.originalHeight || dh;
                    let cornersPct: any;
                    const isPortraitView = window.innerHeight > window.innerWidth;
                    if (isPortraitView && video.videoWidth > video.videoHeight) {
                      const pctArray = corners.map((c: any) => ({
                        x: (c.x / finalW) * 100,
                        y: (c.y / finalH) * 100
                      }));
                      const rotPct = pctArray.map((p: any) => ({
                        x: 100 - p.y,
                        y: p.x
                      }));
                      cornersPct = {
                        tl: rotPct[3], tr: rotPct[0], br: rotPct[1], bl: rotPct[2]
                      };
                    } else {
                      cornersPct = {
                        tl: { x: (corners[0].x / finalW) * 100, y: (corners[0].y / finalH) * 100 },
                        tr: { x: (corners[1].x / finalW) * 100, y: (corners[1].y / finalH) * 100 },
                        br: { x: (corners[2].x / finalW) * 100, y: (corners[2].y / finalH) * 100 },
                        bl: { x: (corners[3].x / finalW) * 100, y: (corners[3].y / finalH) * 100 }
                      };
                    }
                    setDetectedCorners(cornersPct);
                  } else {
                    setDetectedCorners(null);
                  }
                } finally {
                  bitmap.close();
                }
              })
              .catch(() => setDetectedCorners(null))
              .finally(() => {
                (window as any)._isDetectingCornersGlobal = false;
              });
          }
        }
      }
      animationFrameId = requestAnimationFrame(loop);
    };
    animationFrameId = requestAnimationFrame(loop);
    return () => {
      cancelAnimationFrame(animationFrameId);
      setDetectedCorners(null);
    };
  }, [settings.autoDetectEnabled, isReady, cameraError, settings.scannerSubTab]);

  const captureFrame = useCallback(async (options: { targetWidth: number; aspectRatio: number }): Promise<Blob | null> => {
    if (isCapturingInternalRef.current) return null;
    if (!videoRef.current || !stream) {
      console.warn("[CameraContext] captureFrame error: videoRef or stream is missing");
      return null;
    }
    
    isCapturingInternalRef.current = true;
    const video = videoRef.current;
    let canvas = canvasRef.current || document.createElement('canvas');

    try {
      // Explicitly guarantee video dimensions and playing state
      const isReadyForDraw = await ensureVideoReady(video);
      if (!isReadyForDraw) {
        console.warn("[CameraContext] captureFrame cancelled: video element not resolving/playing");
        isCapturingInternalRef.current = false;
        return null;
      }

      const { targetWidth, aspectRatio } = options;
      
      // Robust aspect ratio preserving center crop calculation
      const videoW = video.videoWidth || 640;
      const videoH = video.videoHeight || 480;
      const currentVideoAspect = videoW / videoH;

      let sx = 0;
      let sy = 0;
      let sw = videoW;
      let sh = videoH;

      if (currentVideoAspect > aspectRatio) {
        // Video width is wider than destination crop target — crop horizontal margins
        sw = Math.round(videoH * aspectRatio);
        sx = Math.round((videoW - sw) / 2);
      } else if (currentVideoAspect < aspectRatio) {
        // Video height is taller than destination crop target — crop vertical margins
        sh = Math.round(videoW / aspectRatio);
        sy = Math.round((videoH - sh) / 2);
      }

      // STRICT CONSTRAINTS: Prevent upscaling of the source camera pixels
      let finalTargetWidth = targetWidth;
      if (finalTargetWidth > sw) {
        finalTargetWidth = sw;
      }
      const finalTargetHeight = Math.round(finalTargetWidth / aspectRatio);

      canvas.width = finalTargetWidth;
      canvas.height = finalTargetHeight;
      
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      if (!ctx) {
        isCapturingInternalRef.current = false;
        return null;
      }

      ctx.clearRect(0, 0, finalTargetWidth, finalTargetHeight);
      ctx.drawImage(video, sx, sy, sw, sh, 0, 0, finalTargetWidth, finalTargetHeight);
      
      const blob = await new Promise<Blob | null>((resolve) => {
        canvas.toBlob(
          (blob) => {
            if (!blob) {
              console.error("[CameraContext] canvas.toBlob failed to generate a file");
              resolve(null);
            } else {
              resolve(blob);
            }
          },
          'image/jpeg',
          0.95
        );
      });
      isCapturingInternalRef.current = false;
      return blob;
    } catch (err) {
      isCapturingInternalRef.current = false;
      console.error("[CameraContext] captureFrame runtime error:", err);
      // Fallback: raw stretched grab
      try {
        const targetHeight = Math.round(options.targetWidth / options.aspectRatio);
        canvas.width = options.targetWidth;
        canvas.height = targetHeight;
        const ctx = canvas.getContext('2d');
        if (ctx) {
          ctx.drawImage(video, 0, 0, options.targetWidth, targetHeight);
          return new Promise((resolve) => {
            canvas.toBlob((blob) => resolve(blob), 'image/jpeg', 0.95);
          });
        }
      } catch (fallbackErr) {
        console.error("[CameraContext] captureFrame absolute fallback failed:", fallbackErr);
      }
      return null;
    }
  }, [stream, videoRef, canvasRef]);

  const torchStateRef = useRef<boolean | null>(null);
  const isCapturingInternalRef = useRef(false);

  const applyTorch = useCallback(async (on: boolean) => {
    if (!videoTrack || typeof videoTrack.applyConstraints !== 'function' || !supportsTorch) return;
    if (torchStateRef.current === on) return;
    
    try {
      await videoTrack.applyConstraints({
        advanced: [{ torch: on } as any]
      });
      torchStateRef.current = on;
      addLog(`Torch applied: ${on}`);
    } catch (e) {
      addLog(`Torch attempt failed: ${e}`);
    }
  }, [videoTrack, supportsTorch]);

  const applyFocus = useCallback(async (mode: 'single' | 'continuous') => {
    if (!videoTrack || typeof videoTrack.applyConstraints !== 'function') return;
    try {
      await videoTrack.applyConstraints({
        advanced: [{ focusMode: mode } as any]
      });
      addLog(`Focus applied: ${mode}`);
    } catch (e) {
      addLog(`Focus error: ${e}`);
      console.warn('Focus constraint failed:', e);
    }
  }, [videoTrack]);

  const getBrightness = useCallback((): number => {
    if (!videoRef.current || videoRef.current.readyState !== 4) return 255;
    try {
      const v = videoRef.current;
      const vw = v.videoWidth;
      const vh = v.videoHeight;
      
      const canvas = document.createElement('canvas');
      const size = 100;
      canvas.width = size;
      canvas.height = size;
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      if (!ctx) return 255;
      
      // Sample center region
      ctx.drawImage(v, (vw - size) / 2, (vh - size) / 2, size, size, 0, 0, size, size);
      const data = ctx.getImageData(0, 0, size, size).data;
      
      let totalLuminance = 0;
      for (let i = 0; i < data.length; i += 4) {
        // Human luminance perception formula
        totalLuminance += (data[i] * 0.2126 + data[i + 1] * 0.7152 + data[i + 2] * 0.0722);
      }
      
      return totalLuminance / (data.length / 4);
    } catch (e) {
      return 255;
    }
  }, []);

  // Unified Flashlight/Torch reactivity
  useEffect(() => {
    if (!stream || !isReady || !supportsTorch || !videoTrack) return;

    const handleHardwareFlash = async () => {
      try {
        if (settings.flashMode === 'torch') {
          await applyTorch(true);
        } else if (settings.flashMode === 'off') {
          await applyTorch(false);
        } else if (settings.flashMode === 'auto') {
          // Apply current sensed state
          await applyTorch(isDarkRef.current);
        }
      } catch (err) {
        console.warn('Hardware flash sync failed:', err);
      }
    };

    handleHardwareFlash();
  }, [settings.flashMode, stream, isReady, supportsTorch, videoTrack, applyTorch]);

  // Periodic illumination monitor for auto-torch with hysteresis
  useEffect(() => {
    if (settings.flashMode !== 'auto' || !stream || !isReady) {
      if (settings.flashMode === 'off') isDarkRef.current = false;
      return;
    }

    let lastValues: number[] = [];
    const interval = setInterval(async () => {
      if (isCapturingInternalRef.current) return;
      const b = getBrightness();
      lastValues.push(b);
      if (lastValues.length > 5) lastValues.shift();
      
      const avgBrightness = lastValues.reduce((acc, val) => acc + val, 0) / lastValues.length;
      
      // Hysteresis: turn on if < 90, turn off if > 115
      const isDarkNow = isDarkRef.current ? avgBrightness < 115 : avgBrightness < 90;

      if (isDarkRef.current !== isDarkNow) {
        isDarkRef.current = isDarkNow;
        if (settings.flashMode === 'auto') {
          await applyTorch(isDarkNow);
        }
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [settings.flashMode, getBrightness, stream, isReady, applyTorch]);

  const stopCamera = useCallback(() => {
    shouldBeRunningRef.current = false;
    startingRef.current = false; // Reset starting lock on stop
    startCounterRef.current++; // Invalidate any active starting promises
    restartCounterRef.current++; // Invalidate any active restarts
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => {
        try { track.stop(); } catch (e) {}
      });
      streamRef.current = null;
    }
    setStream(null);
    setVideoTrack(null);
    setIsReady(false);
    
    // Also clear video srcObject
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }
  }, []);

  const startCamera = useCallback(async () => {
    if (settings.usePhoneCamera) {
      addLog("[CameraContext] startCamera bypassed because usePhoneCamera is active");
      return;
    }
    shouldBeRunningRef.current = true;
    if (streamRef.current) return; // Already running
    if (startingRef.current) {
      addLog("[CameraContext] startCamera already in progress, avoiding duplicate getUserMedia call");
      return;
    }
    
    startingRef.current = true;
    setCameraError(false);
    const currentStartId = ++startCounterRef.current;
    const currentRestartId = restartCounterRef.current;
    
    try {
      let resolution = undefined;
      const hdMode = settings.hdMode;
      if (hdMode === 'High') {
         resolution = { width: { ideal: 3840 }, height: { ideal: 2160 } };
         addLog("HD mode: High");
      } else if (hdMode === 'Standard') {
         resolution = { width: { ideal: 1920 }, height: { ideal: 1080 } };
         addLog("HD mode: Standard");
      } else {
         resolution = { width: { ideal: 1280 }, height: { ideal: 720 } }; // Fast
         addLog("HD mode: Fast");
      }

      let newStream;
      try {
        newStream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: 'environment', ...resolution },
          audio: false
        });
      } catch (err) {
        // Fallback checks
        if (!shouldBeRunningRef.current || startCounterRef.current !== currentStartId || restartCounterRef.current !== currentRestartId) {
          return;
        }
        try {
          newStream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: 'environment' },
            audio: false
          });
        } catch (err2) {
          if (!shouldBeRunningRef.current || startCounterRef.current !== currentStartId || restartCounterRef.current !== currentRestartId) {
            return;
          }
          newStream = await navigator.mediaDevices.getUserMedia({
            video: true,
            audio: false
          });
        }
      }

      if (!shouldBeRunningRef.current || startCounterRef.current !== currentStartId || restartCounterRef.current !== currentRestartId) {
        if (newStream) {
          newStream.getTracks().forEach(track => {
            try { track.stop(); } catch (e) {}
          });
        }
        return;
      }

      const track = newStream.getVideoTracks()[0];
      
      // Delay capability check to allow hardware to settle
      setTimeout(() => {
        if (!shouldBeRunningRef.current || startCounterRef.current !== currentStartId || restartCounterRef.current !== currentRestartId) return;
        try {
          const capabilities = track.getCapabilities() as any;
          setSupportsTorch(!!capabilities?.torch);
        } catch (e) {
          setSupportsTorch(false);
        }
      }, 500);

      streamRef.current = newStream;
      setStream(newStream);
      setVideoTrack(track);
      setIsReady(true);

    } catch (err) {
      if (!shouldBeRunningRef.current || startCounterRef.current !== currentStartId || restartCounterRef.current !== currentRestartId) {
         return;
      }
      console.error("Camera error:", err);
      addLog(`Camera error: ${err}`);
      setCameraError(true);
    } finally {
      startingRef.current = false;
    }
  }, [settings.hdMode, settings.usePhoneCamera]);

  const restartCamera = useCallback(async () => {
    if (settings.usePhoneCamera) {
      addLog("[CameraContext] restartCamera bypassed because usePhoneCamera is active");
      stopCamera();
      return;
    }
    const currentRestartId = ++restartCounterRef.current;
    
    // Stop active stream tracks and clear state
    if (streamRef.current) {
      streamRef.current.getTracks().forEach(track => {
        try { track.stop(); } catch (e) {}
      });
      streamRef.current = null;
    }
    setStream(null);
    setVideoTrack(null);
    setIsReady(false);
    if (videoRef.current) {
      videoRef.current.srcObject = null;
    }

    // small delay to let hardware release
    await new Promise(r => setTimeout(r, 100));
    
    // If stopped/cancelled or newer restart is requested, abort
    if (!shouldBeRunningRef.current || restartCounterRef.current !== currentRestartId) {
      return;
    }

    await startCamera();
  }, [startCamera, stopCamera, settings.usePhoneCamera]);

  // Cleanup on unmount
  useEffect(() => {
    return () => stopCamera();
  }, [stopCamera]);

  // Stop camera if usePhoneCamera is toggled ON
  useEffect(() => {
    if (settings.usePhoneCamera) {
      addLog("[CameraContext] usePhoneCamera toggle turned ON, stopping camera");
      stopCamera();
    }
  }, [settings.usePhoneCamera, stopCamera]);

  // Watch for hdMode changes to restart stream if necessary
  // To avoid restarting infinitely, we should keep track of active HD mode
  const hdModeRef = useRef(settings.hdMode);
  useEffect(() => {
    if (hdModeRef.current !== settings.hdMode) {
      hdModeRef.current = settings.hdMode;
      if (streamRef.current) {
        restartCamera();
      }
    }
  }, [settings.hdMode, restartCamera]);

  return (
    <CameraContext.Provider value={{ 
      stream, 
      videoTrack, 
      cameraError, 
      isReady, 
      supportsTorch, 
      startCamera, 
      stopCamera, 
      restartCamera, 
      applyTorch,
      applyFocus,
      videoRef, 
      canvasRef,
      captureFrame,
      detectedCorners,
      setDetectedCorners
    }}>
      {children}
    </CameraContext.Provider>
  );
}

export function useCamera() {
  const context = useContext(CameraContext);
  if (context === undefined) {
    console.warn('useCamera was called outside of a CameraProvider. Providing a safe/stub camera context fallback.');
    return {
      stream: null,
      videoTrack: null,
      cameraError: false,
      isReady: false,
      supportsTorch: false,
      startCamera: async () => {},
      stopCamera: () => {},
      restartCamera: async () => {},
      applyTorch: async () => {},
      applyFocus: async () => {},
      videoRef: { current: null },
      canvasRef: { current: null },
      captureFrame: async () => null,
      detectedCorners: null,
      setDetectedCorners: () => {}
    };
  }
  return context;
}
