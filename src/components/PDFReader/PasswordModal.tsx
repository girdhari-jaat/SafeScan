import { useState } from 'react';
import { Lock } from 'lucide-react';

interface PasswordModalProps {
  isOpen: boolean;
  onClose: () => void;
  onPasswordSubmit: (password: string) => void;
}

export function PasswordModal({ isOpen, onClose, onPasswordSubmit }: PasswordModalProps) {
  const [password, setPassword] = useState('');

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center p-4 backdrop-blur-md bg-[var(--bg-overlay)]">
      <div className="bg-[var(--bg-card)] border border-[var(--border-color)] p-6 rounded-2xl w-full max-w-sm flex flex-col gap-4 shadow-2xl">
        <h3 className="text-[var(--text-primary)] font-bold text-sm flex items-center gap-2">
          <Lock className="w-4 h-4 text-[var(--primary)]" />
          Password Required
        </h3>
        <p className="text-[var(--text-secondary)] text-xs">This PDF is password-protected. Please enter the password to unlock it.</p>
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full bg-[var(--bg-primary)] text-[var(--text-primary)] p-3 rounded-lg border border-[var(--border-color)] text-sm focus:border-[var(--primary)] outline-none"
          placeholder="Password"
        />
        <div className="flex justify-end gap-2 mt-2">
          <button onClick={onClose} className="px-4 py-2 text-[var(--text-secondary)] text-xs font-bold hover:text-[var(--text-primary)] cursor-pointer">Cancel</button>
          <button onClick={() => onPasswordSubmit(password)} className="bg-[var(--primary)] px-4 py-2 rounded-lg text-white text-xs font-bold cursor-pointer transition-all active:scale-95">
            Unlock
          </button>
        </div>
      </div>
    </div>
  );
}
