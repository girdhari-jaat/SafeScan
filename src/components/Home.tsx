import React from 'react';
import Scanner from './Scanner';
import { PageCorners } from '../types';

interface HomeProps {
  documents: any[];
  handleSetDocuments: (docs: any[]) => void;
  setActiveDocId: (id: string | null) => void;
  setScannerSubTab: (tab: 'paper' | 'idcard' | 'grid') => void;
  setCurrentView: (view: 'home' | 'camera' | 'library' | 'editor' | 'pdf') => void;
  handleTriggerFileInput: () => void;

  // Extra props passed from App.tsx to support direct camera on mount
  pages: any[];
  onCapture: (blob: Blob, isBatch: boolean, corners: PageCorners, forceCrop?: boolean) => void;
  onDone: () => void;
  onDeletePage: (pageId: string) => void;
  onRetakePage: (pageId: string, blob: Blob) => void;
  activeDocId: string | null;
}

function Home({
  setCurrentView,
  handleTriggerFileInput,
  pages,
  onCapture,
  onDone,
  onDeletePage,
  onRetakePage,
  activeDocId,
}: HomeProps) {
  // Directly render the paper scanner with no menu cards or onboarding step
  return (
    <div className="flex-grow flex flex-col justify-start h-full bg-black" id="home-viewfinder-container">
      <Scanner
        onCapture={onCapture}
        onFallbackUpload={handleTriggerFileInput}
        onDone={onDone}
        onClose={() => setCurrentView('library')}
        pages={pages ? pages.filter((p) => p.docId === activeDocId) : []}
        onDeletePage={onDeletePage}
        onRetakePage={onRetakePage}
        currentTab="paper"
        onChangeTab={() => {}}
      />
    </div>
  );
}

export default React.memo(Home);
