import React, { useState, useMemo } from 'react';
import { ScanDocument } from '../types';

interface UseDocumentGridHookProps {
  documents: ScanDocument[];
  onRenameDocument: (docId: string, newTitle: string) => void;
}

export type SortOrder = 'newest' | 'oldest' | 'alphabetical';
export type ViewMode = 'grid' | 'list';

export function useDocumentGridHook({ documents, onRenameDocument }: UseDocumentGridHookProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [editingDocId, setEditingDocId] = useState<string | null>(null);
  const [renameValue, setRenameValue] = useState('');
  const [selectedTag, setSelectedTag] = useState<string | null>(null);
  const [docToDelete, setDocToDelete] = useState<ScanDocument | null>(null);
  const [sortOrder, setSortOrder] = useState<SortOrder>('newest');
  const [viewMode, setViewMode] = useState<ViewMode>('grid');
  const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());

  // Extract all unique tags
  const allTags = useMemo(() => Array.from(new Set(documents.flatMap((doc) => doc.tags || []))).filter(Boolean), [documents]);

  // Filter documents by searches and tags
  const filteredDocs = useMemo(() => {
    let result = documents.filter((doc) => {
      const matchesSearch = doc.title.toLowerCase().includes(searchQuery.toLowerCase());
      const matchesTag = selectedTag ? doc.tags?.includes(selectedTag) : true;
      return matchesSearch && matchesTag;
    });

    // Apply sorting
    result.sort((a, b) => {
      if (sortOrder === 'newest') return b.updatedAt - a.updatedAt;
      if (sortOrder === 'oldest') return a.updatedAt - b.updatedAt;
      if (sortOrder === 'alphabetical') return a.title.localeCompare(b.title);
      return 0;
    });

    return result;
  }, [documents, searchQuery, selectedTag, sortOrder]);

  // Start edit title helper
  const handleStartRename = (doc: ScanDocument, e?: React.MouseEvent) => {
    if (e && typeof e.stopPropagation === 'function') {
      e.stopPropagation();
    }
    setEditingDocId(doc.id);
    setRenameValue(doc.title);
  };

  const renameValueRef = React.useRef(renameValue);
  React.useEffect(() => {
    renameValueRef.current = renameValue;
  }, [renameValue]);

  // Commit title edits
  const handleCommitRename = React.useCallback((docId: string) => {
    const val = renameValueRef.current;
    if (val.trim()) {
      onRenameDocument(docId, val.trim());
    }
    setEditingDocId(null);
    setRenameValue('');
  }, [onRenameDocument]);

  const toggleSelect = (docId: string, e?: React.MouseEvent) => {
    if (e) e.stopPropagation();
    setSelectedDocIds(prev => {
      const next = new Set(prev);
      if (next.has(docId)) next.delete(docId);
      else next.add(docId);
      return next;
    });
  };

  const clearSelection = () => setSelectedDocIds(new Set());

  const selectAll = () => {
    if (selectedDocIds.size === filteredDocs.length && filteredDocs.length > 0) {
      clearSelection();
    } else {
      setSelectedDocIds(new Set(filteredDocs.map(d => d.id)));
    }
  };

  // Filtered and grouped docs
  const groupedDocs = useMemo(() => {
    const groups: { [key: string]: ScanDocument[] } = {};
    
    filteredDocs.forEach(doc => {
      const date = new Date(doc.updatedAt);
      const now = new Date();
      let key = 'Earlier';
      
      if (date.toDateString() === now.toDateString()) {
        key = 'Today';
      } else {
        const yesterday = new Date();
        yesterday.setDate(now.getDate() - 1);
        if (date.toDateString() === yesterday.toDateString()) {
          key = 'Yesterday';
        } else if (now.getTime() - date.getTime() < 7 * 24 * 60 * 60 * 1000) {
          key = 'This Week';
        }
      }
      
      if (!groups[key]) groups[key] = [];
      groups[key].push(doc);
    });
    
    return groups;
  }, [filteredDocs]);

  return {
    searchQuery,
    setSearchQuery,
    editingDocId,
    setEditingDocId,
    renameValue,
    setRenameValue,
    selectedTag,
    setSelectedTag,
    docToDelete,
    setDocToDelete,
    allTags,
    filteredDocs,
    groupedDocs,
    sortOrder,
    setSortOrder,
    viewMode,
    setViewMode,
    selectedDocIds,
    toggleSelect,
    clearSelection,
    selectAll,
    handleStartRename,
    handleCommitRename
  };
}
