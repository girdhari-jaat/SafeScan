// AUDITED: Fixed canvas leaks and removed unused exports
import { useCallback, useRef } from 'react';

interface UseSettingsHookProps {
  settings: {
    showGrid: boolean;
    clickSound: boolean;
    autoOrientation: boolean;
    autoCrop: boolean;
    shadowRemoveEnabled: boolean;
    doubleFocusEnabled: boolean;
    usePhoneCamera: boolean;
    batterySaverEnabled: boolean;
    batchScan: boolean;
    autoRotation: boolean;
  };
  onUpdateSetting: (key: any, value: boolean | ((prev: boolean) => boolean)) => void;
  flashModeState: 'off' | 'auto' | 'torch';
  onToggleFlashState: (nextMode: 'off' | 'auto' | 'torch') => void;
  onUpdateResolutionState: (mode: 'Fast' | 'Standard' | 'High') => void;
  currentTab?: 'paper' | 'idcard' | 'grid';
}

export function useSettingsHook({
  onUpdateSetting,
  flashModeState,
  onToggleFlashState,
  onUpdateResolutionState,
  currentTab = 'paper',
}: Omit<UseSettingsHookProps, 'isDark'>) {
  const resolutionTimeoutRef = useRef<any>(null);
  
  // 1. Toggle Flashlight/Torch logic - purely updates state, hardware reacts in CameraContext
  const toggleFlash = useCallback(async (_stream: MediaStream | null) => {
    let nextMode: 'off' | 'auto' | 'torch' = 'off';
    if (flashModeState === 'off') {
      nextMode = 'auto';
    } else if (flashModeState === 'auto') {
      nextMode = 'torch';
    } else {
      nextMode = 'off';
    }

    onToggleFlashState(nextMode);
  }, [flashModeState, onToggleFlashState]);

  // 2. Set Resolution ('Fast' | 'Standard' | 'High') natively by applying constraints to the MediaStream track
  const setResolution = useCallback(async (stream: MediaStream | null, mode: 'Fast' | 'Standard' | 'High') => {
    onUpdateResolutionState(mode);

    if (resolutionTimeoutRef.current) clearTimeout(resolutionTimeoutRef.current);
    resolutionTimeoutRef.current = setTimeout(async () => {
      if (!stream) return;
      const track = stream.getVideoTracks()[0];
      if (!track || typeof track.applyConstraints !== 'function') return;

      const getHdConstraints = (m: 'Fast' | 'Standard' | 'High') => {
        // Use A4 ratio (1.414:1) for Paper, Pakistani CNIC ratio (1.585:1) for ID Card
        if (currentTab === 'paper') {
          switch(m) {
            case 'Fast': return { width: { ideal: 1754 }, height: { ideal: 1240 } };
            case 'Standard': return { width: { ideal: 2339 }, height: { ideal: 1654 } };
            case 'High': return { width: { ideal: 3508 }, height: { ideal: 2480 } };
          }
        } else {
          switch(m) {
            case 'Fast': return { width: { ideal: 1240 }, height: { ideal: 783 } };
            case 'Standard': return { width: { ideal: 1654 }, height: { ideal: 1044 } };
            case 'High': return { width: { ideal: 2040 }, height: { ideal: 1287 } };
          }
        }
      };

      try {
        await track.applyConstraints(getHdConstraints(mode));
      } catch (err) {
        console.warn('Unable to adjust native hardware stream resolution to target range:', err);
      }
    }, 300);
  }, [onUpdateResolutionState, currentTab]);

  // 3. Toggle Grid Lines setting in local/persisted app options
  const toggleGridLines = useCallback(() => {
    onUpdateSetting('showGrid', (prev: boolean) => !prev);
  }, [onUpdateSetting]);

  return {
    toggleFlash,
    setResolution,
    toggleGridLines
  };
}
