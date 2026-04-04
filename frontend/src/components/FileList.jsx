import { useState } from 'react';
import { FiGrid, FiList, FiDownload, FiTrash2, FiInbox } from 'react-icons/fi';
import FileCard, { iconFor, colorFor, formatSize } from './FileCard.jsx';

export default function FileList({ entries, onDownload, onDelete, title = 'Files' }) {
  const [view, setView] = useState('grid');

  if (!entries || entries.length === 0) {
    return (
      <div className="flex flex-col items-center py-24 text-gray-400 select-none">
        <div className="w-20 h-20 bg-gray-100 rounded-2xl flex items-center justify-center mb-4">
          <FiInbox size={36} className="text-gray-300" />
        </div>
        <h3 className="text-base font-semibold text-gray-500 mb-1">No files yet</h3>
        <p className="text-sm text-gray-400">Upload files using the button above.</p>
      </div>
    );
  }

  const sorted = [...entries].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''));

  return (
    <div>
      {/* Header row */}
      <div className="flex items-center justify-between mb-5">
        <div>
          <h2 className="text-[17px] font-semibold text-gray-800">{title}</h2>
          <p className="text-xs text-gray-400 mt-0.5">{entries.length} file{entries.length !== 1 ? 's' : ''}</p>
        </div>
        <div className="flex gap-1 bg-gray-100 rounded-lg p-1">
          <button
            onClick={() => setView('grid')}
            className={`p-1.5 rounded-md text-sm transition ${
              view === 'grid' ? 'bg-white shadow-sm text-[#0078d4]' : 'text-gray-400 hover:text-gray-600'
            }`}
          >
            <FiGrid />
          </button>
          <button
            onClick={() => setView('list')}
            className={`p-1.5 rounded-md text-sm transition ${
              view === 'list' ? 'bg-white shadow-sm text-[#0078d4]' : 'text-gray-400 hover:text-gray-600'
            }`}
          >
            <FiList />
          </button>
        </div>
      </div>

      {/* Grid view */}
      {view === 'grid' ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-3">
          {sorted.map((entry) => (
            <FileCard key={entry.name} entry={entry} onDownload={onDownload} onDelete={onDelete} />
          ))}
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-gray-100 overflow-hidden shadow-sm">
          <table className="min-w-full divide-y divide-gray-100 text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Name</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">Size</th>
                <th className="px-4 py-3 text-left font-medium text-gray-500">
                  Uploaded At
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500 w-24">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {sorted.map((entry) => {
                const Icon = iconFor(entry);
                const colorClass = colorFor(entry);
                return (
                  <tr key={entry.name} className="hover:bg-gray-50 transition-colors">
                    <td className="px-4 py-3">
                      <span
                        className="flex items-center gap-3 cursor-pointer"
                        onClick={() => onDownload(entry)}
                      >
                        <span className={`w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ${colorClass}`}>
                          <Icon size={15} />
                        </span>
                        <span className="font-medium text-gray-800">{entry.name}</span>
                      </span>
                    </td>
                    <td className="px-4 py-3 text-gray-500">{formatSize(entry.sizeBytes)}</td>
                    <td className="px-4 py-3 text-gray-500 font-mono text-xs">
                      {entry.uploadedAtMs ? (() => {
                        const d = new Date(entry.uploadedAtMs);
                        const hh = String(d.getHours()).padStart(2, '0');
                        const mm = String(d.getMinutes()).padStart(2, '0');
                        const ss = String(d.getSeconds()).padStart(2, '0');
                        const ms = String(d.getMilliseconds()).padStart(3, '0');
                        return `${hh}:${mm}:${ss}:${ms}`;
                      })() : '—'}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex gap-1">
                        <button
                          title="Download"
                          onClick={() => onDownload(entry)}
                          className="p-1.5 rounded-lg text-gray-400 hover:bg-[#0078d4] hover:text-white transition"
                        >
                          <FiDownload size={14} />
                        </button>
                        <button
                          title="Delete"
                          onClick={() => onDelete(entry)}
                          className="p-1.5 rounded-lg text-gray-400 hover:bg-red-500 hover:text-white transition"
                        >
                          <FiTrash2 size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
