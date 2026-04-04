import { useState, useEffect, useCallback } from 'react';
import { FiUploadCloud } from 'react-icons/fi';
import FileList from '../components/FileList.jsx';
import UploadModal from '../components/UploadModal.jsx';
import { listFiles, downloadFile, deleteFile } from '../services/api.js';

export default function Dashboard({ uploadModalOpen, setUploadModalOpen }) {
  const [entries, setEntries] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const fetchEntries = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await listFiles('/');
      setEntries(Array.isArray(data) ? data : []);
    } catch (err) {
      if (err?.response?.status === 503) {
        setError('Read quorum not reached — too many nodes unavailable');
      } else {
        setError('Failed to load files — is the backend running?');
      }
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchEntries(); }, [fetchEntries]);

  const handleDownload = async (entry) => {
    try {
      const blob = await downloadFile('/' + entry.name);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = entry.name; a.click();
      URL.revokeObjectURL(url);
    } catch { setError('Download failed'); }
  };

  const handleDelete = async (entry) => {
    if (!window.confirm(`Delete "${entry.name}"?`)) return;
    try { await deleteFile('/' + entry.name); fetchEntries(); }
    catch { setError('Delete failed'); }
  };

  return (
    <div>
      {/* Page header */}
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">My Files</h1>
          <p className="text-sm text-gray-400 mt-0.5">Distributed across all cluster nodes</p>
        </div>
        <button
          onClick={() => setUploadModalOpen(true)}
          className="flex items-center gap-2 text-sm font-semibold bg-[#0078d4] hover:bg-[#106ebe] text-white px-4 py-2.5 rounded-xl transition shadow-sm"
        >
          <FiUploadCloud size={15} /> Upload Files
        </button>
      </div>

      {error && (
        <div className="mb-4 bg-red-50 border border-red-100 text-red-700 px-4 py-3 rounded-xl text-sm">{error}</div>
      )}

      {loading ? (
        <div className="flex flex-col items-center gap-3 py-24 text-gray-400 text-sm">
          <div className="w-10 h-10 border-4 border-gray-100 border-t-[#0078d4] rounded-full animate-spin" />
          Loading files…
        </div>
      ) : (
        <FileList
          entries={entries ?? []}
          title="All Files"
          onDownload={handleDownload}
          onDelete={handleDelete}
        />
      )}

      {uploadModalOpen && (
        <UploadModal
          onClose={() => setUploadModalOpen(false)}
          onUploaded={fetchEntries}
        />
      )}
    </div>
  );
}