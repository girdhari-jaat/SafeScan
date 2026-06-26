import { useState } from 'react';

interface UseExportModalProps {
  defaultTitle: string;
}

export function useExportModal({ defaultTitle }: UseExportModalProps) {
  const [title, setTitle] = useState(defaultTitle);
  const [password, setPassword] = useState('');
  const [pageSize, setPageSize] = useState<'a4' | 'letter' | 'fit'>('a4');
  const [orientation, setOrientation] = useState<'portrait' | 'landscape' | 'auto'>('auto');
  const [quality, setQuality] = useState<number>(0.9);

  return {
    title,
    setTitle,
    password,
    setPassword,
    pageSize,
    setPageSize,
    orientation,
    setOrientation,
    quality,
    setQuality,
  };
}
