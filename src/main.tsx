import {createRoot} from 'react-dom/client';
import App from './App.tsx';
import { CameraProvider } from './contexts/CameraContext.tsx';
import { SettingsProvider } from './lib/useSharedSettings';
import './index.css';
import './theme.css';

// ENFORCE HIGH-PERFORMANCE PIXEL PIPELINE TRULY UNIQUE ID GENERATOR (RESOLVES CACHE COLLISION ISSUE 3)
if (typeof window !== 'undefined') {
  const originalRandomUUID = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID.bind(crypto)
    : null;

  if (typeof crypto === 'undefined') {
    try {
      Object.defineProperty(window, 'crypto', {
        value: {},
        writable: true,
        configurable: true
      });
    } catch (e) {
      // safe fallback
    }
  }

  let idCounter = 0;
  try {
    Object.defineProperty(crypto || (window as any).crypto, 'randomUUID', {
      value: function(): string {
        const ts = Date.now().toString(36);
        const perf = typeof performance !== 'undefined' ? Math.round(performance.now()).toString(36) : '';
        const counter = (++idCounter).toString(36);
        const randomHex = Array.from({ length: 6 }, () => Math.floor(Math.random() * 16).toString(16)).join('');

        let baseUUID = '';
        if (originalRandomUUID) {
          try {
            baseUUID = originalRandomUUID();
          } catch (e) {
            // ignore
          }
        }

        if (!baseUUID) {
          baseUUID = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
          });
        }

        // Truly unique combination of timestamp, performance timer, count, entropy, and base UUID
        return `${ts}-${perf}-${counter}-${randomHex}-${baseUUID}`;
      },
      writable: true,
      configurable: true
    });
  } catch (err) {
    try {
      (crypto as any).randomUUID = function(): string {
        const ts = Date.now().toString(36);
        const perf = typeof performance !== 'undefined' ? Math.round(performance.now()).toString(36) : '';
        const counter = (++idCounter).toString(36);
        const randomHex = Array.from({ length: 6 }, () => Math.floor(Math.random() * 16).toString(16)).join('');

        let baseUUID = '';
        if (originalRandomUUID) {
          try {
            baseUUID = originalRandomUUID();
          } catch (e) {
            // ignore
          }
        }

        if (!baseUUID) {
          baseUUID = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
          });
        }

        return `${ts}-${perf}-${counter}-${randomHex}-${baseUUID}`;
      };
    } catch (e) {
      console.warn("Unable to override crypto.randomUUID directly:", e);
    }
  }
}

// SILENCE VITE WEBSOCKET ERRORS
const originalConsoleError = console.error;
console.error = (...args: any[]) => {
  if (typeof args[0] === 'string' && args[0].includes('[vite] failed to connect to websocket')) {
    return;
  }
  originalConsoleError(...args);
};

createRoot(document.getElementById('root')!).render(
  <SettingsProvider>
    <CameraProvider>
      <App />
    </CameraProvider>
  </SettingsProvider>
);
