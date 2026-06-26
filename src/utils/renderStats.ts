export const globalRenderCountRef = { current: {} as Record<string, number> };
export const globalLogsRef = { current: [] as string[] };
const listeners = new Set<() => void>();

(window as any).renderCountRef = globalRenderCountRef;
(window as any).logsRef = globalLogsRef;

export const addLog = (log: string) => {
  const lowercaseLog = log.toLowerCase();
  if (
    lowercaseLog.includes('websocket') ||
    lowercaseLog.includes('failed to connect to') ||
    lowercaseLog.includes('websocket closed')
  ) {
    return;
  }
  globalLogsRef.current.push(log);
  if (globalLogsRef.current.length > 50) globalLogsRef.current.shift();
  listeners.forEach((listener) => listener());
};

export const addError = (error: string) => {
  addLog(`🔴 ERR: ${error}`);
};

export const addWarning = (warning: string) => {
  addLog(`🟡 WRN: ${warning}`);
};

export const clearLogs = () => {
  globalLogsRef.current = [];
  listeners.forEach((listener) => listener());
};

export const subscribeToLogs = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

export const showRenderStats = () => {
  // console.table(globalRenderCountRef.current);
};
(window as any).showRenderStats = showRenderStats;
