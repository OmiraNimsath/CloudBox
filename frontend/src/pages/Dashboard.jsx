/**
 * CloudBox — Dashboard page (file manager).
 *
 * Breadcrumb navigation, folder browsing, download, delete, and upload modal.
 */

import { useState, useEffect, useCallback } from 'react';
import { FiHome, FiChevronRight } from 'react-icons/fi';
import FileList from '../components/FileList.jsx';
import UploadModal from '../components/UploadModal.jsx';
import { listFiles, downloadFile, deleteFile } from '../services/api.js';

export default function Dashboard({ uploadModalOpen, setUploadModalOpen }) {
  const [currentPath, setCurrentPath] = useState('/');
  const [entries, setEntries] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  /* ── Fetch folder contents ───────────────────────────────── */
  const fetchEntries = useCallback(async (path) => {
    setLoading(true);
    setError('');
    try {
      const data = await listFiles(path);
      setEntries(Array.isArray(data) ? data : (data?.entries ?? []));
    } catch {
      setError('Failed to load files');
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchEntries(currentPath); }, [currentPath, fetchEntries]);

  /* ── Breadcrumb pieces ───────────────────────────────────── */
  const breadcrumbs = [];
  if (currentPath !== '/') {
    const parts = currentPath.replace(/^\//, '').replace(/\/$/, '').split('/');
    let accum = '/';
    for (const p of parts) {
      accum += p + '/';
      breadcrumbs.push({ label: p, path: accum });
    }
  }

  /* ── Actions ─────────────────────────────────────────────── */
  const handleNavigate = (entry) => {
    const next = currentPath === '/' ? `/${entry.name}/` : `${currentPath}${entry.name}/`;
    setCurrentPath(next);
  };

  const handleDownload = async (entry) => {
    try {
      const filePath = currentPath === '/' ? `/${entry.name}` : `${currentPath}${entry.name}`;
      const blob = await downloadFile(filePath);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = entry.name;
      a.click();
      URL.revokeObjectURL(url);
    } catch {
      setError('Download failed');
    }
  };

  const handleDelete = async (entry) => {
    if (!window.confirm(`Delete "${entry.name}"?`)) return;
    try {
      const filePath = currentPath === '/' ? `/${entry.name}` : `${currentPath}${entry.name}`;
      await deleteFile(filePath);
      fetchEntries(currentPath);
    } catch {
      setError('Delete failed');
    }
  };

  const folderTitle = currentPath === '/' ? 'My Files' : currentPath.replace(/\/$/, '').split('/').pop();

  return (
    <>
      {/* Breadcrumb */}
      <nav className="flex items-center gap-1.5 mb-5 text-sm text-gray-500">
        <span className="flex items-center gap-1 cursor-pointer hover:text-[#0078d4] transition" onClick={() => setCurrentPath('/')}>
          <FiHome /> My Files
        </span>
        {breadcrumbs.map((bc) => (
          <span key={bc.path} className="flex items-center gap-1">
            <FiChevronRight className="text-gray-300" />
            <span className="cursor-pointer hover:text-[#0078d4] transition" onClick={() => setCurrentPath(bc.path)}>
              {bc.label}
            </span>
          </span>
        ))}
      </nav>

      {/* Error banner */}
      {error && <div className="mb-4 bg-red-50 text-red-700 px-3 py-2 rounded text-[13px]">{error}</div>}

      {/* File listing */}
      {loading ? (
        <div className="flex flex-col items-center gap-3 py-12 text-gray-400 text-sm">
          <div className="w-8 h-8 border-3 border-gray-200 border-t-[#0078d4] rounded-full animate-spin" />
          Loading…
        </div>
      ) : (
        <FileList
          entries={entries ?? []}
          title={folderTitle}
          onNavigate={handleNavigate}
          onDownload={handleDownload}
          onDelete={handleDelete}
        />
      )}

      {/* Upload modal */}
      {uploadModalOpen && (
        <UploadModal
          currentPath={currentPath}
          onClose={() => setUploadModalOpen(false)}
          onUploaded={() => fetchEntries(currentPath)}
        />
      )}
    </>
  );
}
