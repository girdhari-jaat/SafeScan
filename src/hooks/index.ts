import { useState, useEffect } from 'react';

/**
 * Hook to debounce value changes by a specified latency (e.g. 80ms)
 */
export function useDebounce<T>(value: T, delay: number = 80): T {
  const [debouncedValue, setDebouncedValue] = useState<T>(value);

  useEffect(() => {
    const handler = setTimeout(() => {
      setDebouncedValue(value);
    }, delay);

    return () => {
      clearTimeout(handler);
    };
  }, [value, delay]);

  return debouncedValue;
}

