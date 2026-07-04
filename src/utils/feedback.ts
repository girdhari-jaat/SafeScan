import { addLog } from "./renderStats";

let globalAudioCtx: AudioContext | null = null;

/**
 * Lazily obtains and caches the Web Audio Context, resolving browser-level locks.
 */
const getAudioContext = (): AudioContext | null => {
  if (typeof window === "undefined") return null;
  const AudioContextClass =
    window.AudioContext || (window as any).webkitAudioContext;
  if (!AudioContextClass) return null;
  if (!globalAudioCtx) {
    try {
      globalAudioCtx = new AudioContextClass();
    } catch (err) {
      console.warn("[Feedback] Failed to construct AudioContext:", err);
    }
  }
  return globalAudioCtx;
};

// Automatic AudioContext unlock mechanism on user tap/touch/click
if (typeof window !== "undefined") {
  const unlockAudio = () => {
    const ctx = getAudioContext();
    if (ctx && ctx.state === "suspended") {
      ctx.resume().then(() => {
        addLog("[Feedback] AudioContext successfully resumed on user interaction.");
        removeListeners();
      }).catch((err) => {
        console.warn("[Feedback] Failed to resume AudioContext:", err);
      });
    } else if (ctx) {
      removeListeners();
    }
  };

  const removeListeners = () => {
    window.removeEventListener("click", unlockAudio);
    window.removeEventListener("touchstart", unlockAudio);
    window.removeEventListener("mousedown", unlockAudio);
  };

  window.addEventListener("click", unlockAudio, { passive: true });
  window.addEventListener("touchstart", unlockAudio, { passive: true });
  window.addEventListener("mousedown", unlockAudio, { passive: true });
}

/**
 * Triggers a native haptic feedback vibration.
 * Prefers Capacitor Haptics plugin on mobile, with graceful fallback to standard Web Vibrate.
 */
export const triggerVibration = async (duration = 60) => {
  const isCapacitor = typeof window !== "undefined" && (window as any).Capacitor;
  if (isCapacitor) {
    try {
      const { Haptics } = await import("@capacitor/haptics");
      await Haptics.vibrate({ duration });
      addLog(`[Feedback] Triggered Capacitor native vibration: ${duration}ms`);
    } catch (err) {
      console.warn("[Feedback] Native haptics failed, trying web fallback:", err);
      fallbackWebVibrate(duration);
    }
  } else {
    fallbackWebVibrate(duration);
  }
};

const fallbackWebVibrate = (duration: number) => {
  if (typeof navigator !== "undefined" && navigator.vibrate) {
    try {
      navigator.vibrate(duration);
    } catch (err) {
      console.warn("[Feedback] HTML5 Vibrate failed:", err);
    }
  }
};

/**
 * Generates an high-fidelity offline-ready shutter beep using raw synth frequencies.
 */
export const triggerBeep = () => {
  try {
    const ctx = getAudioContext();
    if (!ctx) return;
    
    if (ctx.state === "suspended") {
      ctx.resume();
    }

    const bufferSize = ctx.sampleRate * 0.1; // 100ms click
    const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate);
    const data = buffer.getChannelData(0);

    // Write noise
    for (let i = 0; i < bufferSize; i++) {
      data[i] = Math.random() * 2 - 1;
    }

    const noise = ctx.createBufferSource();
    noise.buffer = buffer;

    const filter = ctx.createBiquadFilter();
    filter.type = "bandpass";
    filter.frequency.value = 1000;

    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0.5, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.08);

    noise.connect(filter);
    filter.connect(gain);
    gain.connect(ctx.destination);

    noise.start();
    addLog("[Feedback] Synth click beep audio triggered.");
  } catch (err) {
    console.warn("[Feedback] Synth play failed:", err);
  }
};

/**
 * Requests Notification permissions explicitly on mobile.
 * Required on Android 13+ for proper system alerts, sound routes, and vibration channels.
 */
export const requestNotificationPermissions = async () => {
  const isCapacitor = typeof window !== "undefined" && (window as any).Capacitor;
  if (isCapacitor) {
    try {
      const { LocalNotifications } = await import("@capacitor/local-notifications");
      const status = await LocalNotifications.checkPermissions();
      addLog(`[Feedback] Local Notification current status: ${status.display}`);
      if (status.display !== "granted") {
        const result = await LocalNotifications.requestPermissions();
        addLog(`[Feedback] Requested Notification permission: ${result.display}`);
        return result.display === "granted";
      }
      return true;
    } catch (err) {
      console.warn("[Feedback] Failed to request local notification permissions:", err);
      return false;
    }
  } else {
    if (typeof Notification !== "undefined" && Notification.permission === "default") {
      try {
        const result = await Notification.requestPermission();
        return result === "granted";
      } catch (e) {
        console.warn("[Feedback] Web notification request failed:", e);
        return false;
      }
    }
    return typeof Notification !== "undefined" ? Notification.permission === "granted" : false;
  }
};
