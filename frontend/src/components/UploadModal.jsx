/**
 * CloudBox — Upload modal (drag-and-drop + browse).
 */

import { useState, useRef, useCallback } from 'react';
import { FiUploadCloud, FiX, FiFile, FiTrash2 } from 'react-icons/fi';
import { uploadFile } from '../services/api.js';

const MAX_SIZE = 100 * 1024 * 1024; // 100 MB

export default function UploadModal({ onClose, onUploaded }) {
  const [files, setFiles] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [dragging, setDragging] = useState(false);
  const inputRef = useRef(null);

  const addFiles = useCallback((incoming) => {
    for (const f of incoming) {
      if (f.size > MAX_SIZE) {
        setError(`${f.name} exceeds 100 MB limit`);
        return;
      }
    }
    setFiles((prev) => [...prev, ...incoming]);
    setError('');
  }, []);

  const removeFile = (idx) => setFiles((prev) => prev.filter((_, i) => i !== idx));

  /* ── Drag handlers ───────────────────────────────────────── */
  const onDragOver = (e) => { e.preventDefault(); setDragging(true); };
  const onDragLeave = () => setDragging(false);
  const onDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    if (e.dataTransfer.files.length) addFiles(Array.from(e.dataTransfer.files));
  };
  const onBrowse = (e) => {
    if (e.target.files.length) addFiles(Array.from(e.target.files));
  };

  /* ── Upload handler ──────────────────────────────────────── */
  const handleUpload = async () => {
    if (files.length === 0) return;
    setUploading(true);
    setError('');
    setSuccess('');
    try {
      for (const file of files) {
        await uploadFile(file, '/');
      }
      setSuccess(`${files.length} file(s) uploaded successfully`);
      setFiles([]);
      onUploaded?.();
    } catch (err) {
      setError(err?.response?.data?.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/45 flex items-center justify-center z-50" onClick={onClose}>
      <div className="bg-white rounded-lg w-[520px] max-h-[80vh] overflow-y-auto shadow-xl" onClick={(e) => e.stopPropagation()}>
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200">
          <h3 className="text-lg font-semibold">Upload Files</h3>
          <button className="text-gray-400 hover:bg-gray-100 rounded p-1 transition" onClick={onClose}>
            <FiX className="text-lg" />
          </button>
        </div>

        {/* Body */}
        <div className="p-5">
          {/* Drop zone */}
          <div
            className={`border-2 border-dashed rounded-md p-8 text-center cursor-pointer transition
              ${dragging ? 'border-[#0078d4] bg-blue-50' : 'border-gray-200 hover:border-[#0078d4]'}`}
            onDragOver={onDragOver}
            onDragLeave={onDragLeave}
            onDrop={onDrop}
            onClick={() => inputRef.current?.click()}
          >
            <FiUploadCloud className="text-4xl text-[#0078d4] mx-auto mb-3" />
            <p className="text-sm text-gray-500">
              Drag & drop files here, or <span className="text-[#0078d4] font-semibold">browse</span>
            </p>
            <p className="text-xs text-gray-400 mt-1">Max 100 MB per file</p>
            <input ref={inputRef} type="file" multiple hidden onChange={onBrowse} />
          </div>

          {/* Selected files */}
          {files.length > 0 && (
            <div className="mt-4 max-h-40 overflow-y-auto space-y-1">
              {files.map((f, i) => (
                <div key={`${f.name}-${i}`} className="flex items-center justify-between px-2 py-1.5 rounded hover:bg-gray-50 text-[13px]">
                  <div className="flex items-center gap-2 overflow-hidden">
                    <FiFile className="text-gray-400 shrink-0" />
                    <span className="truncate">{f.name}</span>
                  </div>
                  <button onClick={() => removeFile(i)} className="text-gray-300 hover:text-red-500 p-0.5">
                    <FiTrash2 />
                  </button>
                </div>
              ))}
            </div>
          )}

          {/* Feedback */}
          {error && <div className="mt-3 bg-red-50 text-red-700 px-3 py-2 rounded text-[13px]">{error}</div>}
          {success && <div className="mt-3 bg-green-50 text-green-700 px-3 py-2 rounded text-[13px]">{success}</div>}
        </div>

        {/* Footer */}
        <div className="flex justify-end gap-2 px-5 py-3 border-t border-gray-200">
          <button onClick={onClose} className="px-4 py-1.5 text-[13px] font-semibold bg-gray-100 hover:bg-gray-200 rounded border border-gray-200 transition">
            Cancel
          </button>
          <button
            onClick={handleUpload}
            disabled={files.length === 0 || uploading}
            className="px-4 py-1.5 text-[13px] font-semibold bg-[#0078d4] hover:bg-[#106ebe] text-white rounded transition disabled:bg-[#a0c4e8] disabled:cursor-not-allowed"
          >
            {uploading ? 'Uploading…' : 'Upload'}
          </button>
        </div>
      </div>
    </div>
  );
}
