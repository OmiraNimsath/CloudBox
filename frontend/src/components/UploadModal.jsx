import { useState, useRef, useCallback } from 'react';
import { FiUploadCloud, FiX, FiFile, FiTrash2, FiCheckCircle } from 'react-icons/fi';
import { uploadFile } from '../services/api.js';
import { formatSize } from './FileCard.jsx';

const MAX_SIZE = 100 * 1024 * 1024;

export default function UploadModal({ onClose, onUploaded }) {
  const [files, setFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef(null);

  const addFiles = useCallback((incoming) => {
    for (const f of incoming) {
      if (f.size > MAX_SIZE) { setError(`${f.name} exceeds 100 MB limit`); return; }
    }
    setFiles((prev) => [...prev, ...incoming]);
    setError('');
  }, []);

  const removeFile = (idx) => setFiles((prev) => prev.filter((_, i) => i !== idx));

  const onDragOver = (e) => { e.preventDefault(); setDragging(true); };
  const onDragLeave = () => setDragging(false);
  const onDrop = (e) => {
    e.preventDefault(); setDragging(false);
    if (e.dataTransfer.files.length) addFiles(Array.from(e.dataTransfer.files));
  };
  const onBrowse = (e) => { if (e.target.files.length) addFiles(Array.from(e.target.files)); };

  const handleUpload = async () => {
    if (files.length === 0) return;
    setUploading(true); setError(''); setSuccess('');
    try {
      for (const file of files) await uploadFile(file, '/');
      setSuccess(`${files.length} file${files.length > 1 ? 's' : ''} uploaded successfully`);
      setFiles([]);
      onUploaded?.();
      setTimeout(() => onClose?.(), 1500);
    } catch (err) {
      setError(err?.response?.data?.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4" onClick={onClose}>
      <div className="bg-white rounded-2xl w-130 max-h-[85vh] overflow-y-auto shadow-2xl" onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h3 className="text-lg font-semibold text-gray-800">Upload Files</h3>
          <button className="text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-lg p-1.5 transition" onClick={onClose}>
            <FiX size={18} />
          </button>
        </div>

        <div className="p-6 space-y-4">
          {/* Drop zone */}
          <div
            className={`border-2 border-dashed rounded-xl p-10 text-center cursor-pointer transition-all
              ${dragging ? 'border-[#0078d4] bg-blue-50' : 'border-gray-200 hover:border-[#0078d4] hover:bg-gray-50'}`}
            onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop}
            onClick={() => inputRef.current?.click()}
          >
            <div className={`w-14 h-14 rounded-2xl mx-auto mb-4 flex items-center justify-center transition
              ${dragging ? 'bg-[#0078d4]' : 'bg-gray-100'}`}>
              <FiUploadCloud size={26} className={dragging ? 'text-white' : 'text-gray-400'} />
            </div>
            <p className="text-sm font-medium text-gray-700 mb-1">
              Drop files here or <span className="text-[#0078d4]">browse</span>
            </p>
            <p className="text-xs text-gray-400">Max 100 MB per file</p>
            <input ref={inputRef} type="file" multiple hidden onChange={onBrowse} />
          </div>

          {/* Selected files */}
          {files.length > 0 && (
            <div className="space-y-2 max-h-48 overflow-y-auto">
              {files.map((f, i) => (
                <div key={`${f.name}-${i}`} className="flex items-center justify-between bg-gray-50 rounded-xl px-3 py-2.5">
                  <div className="flex items-center gap-2.5 overflow-hidden">
                    <div className="w-8 h-8 bg-[#0078d4]/10 rounded-lg flex items-center justify-center shrink-0">
                      <FiFile size={14} className="text-[#0078d4]" />
                    </div>
                    <div className="overflow-hidden">
                      <p className="text-[13px] font-medium text-gray-800 truncate">{f.name}</p>
                      <p className="text-[11px] text-gray-400">{formatSize(f.size)}</p>
                    </div>
                  </div>
                  <button onClick={() => removeFile(i)} className="text-gray-400 hover:text-red-500 transition p-1 shrink-0">
                    <FiTrash2 size={14} />
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Feedback */}
          {error && (
            <div className="bg-red-50 border border-red-100 text-red-700 rounded-xl px-4 py-2.5 text-sm">{error}</div>
          )}
          {success && (
            <div className="bg-green-50 border border-green-100 text-green-700 rounded-xl px-4 py-2.5 text-sm flex items-center gap-2">
              <FiCheckCircle /> {success}
            </div>
          )}

          {/* Actions */}
          <div className="flex gap-3 pt-1">
            <button
              onClick={onClose}
              className="flex-1 px-4 py-2.5 rounded-xl border border-gray-200 text-sm font-medium text-gray-600 hover:bg-gray-50 transition"
            >
              Cancel
            </button>
            <button
              onClick={handleUpload}
              disabled={files.length === 0 || uploading}
              className="flex-1 px-4 py-2.5 rounded-xl bg-[#0078d4] text-white text-sm font-semibold hover:bg-[#106ebe] transition disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {uploading ? 'Uploading…' : files.length > 0 ? `Upload ${files.length} file${files.length > 1 ? 's' : ''}` : 'Upload'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
