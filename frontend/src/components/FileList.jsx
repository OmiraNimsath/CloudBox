/**
 * CloudBox — FileList component.
 *
 * Renders files/folders in Grid or Table (list) view with sort, download, delete.
 */

import { useState } from 'react';
import { FiGrid, FiList, FiDownload, FiTrash2, FiFolder } from 'react-icons/fi';
import FileCard, { iconFor, formatSize } from './FileCard.jsx';

export default function FileList({
  entries,
  onNavigate,
  onDownload,
  onDelete,
  title = 'Files',
}) {
  const [view, setView] = useState('grid');

  if (!entries || entries.length === 0) {
    return (
      <div className="flex flex-col items-center py-16 text-gray-400">
        <FiFolder className="text-6xl mb-4" />
        <h3 className="text-lg font-semibold text-gray-600 mb-1">This folder is empty</h3>
        <p className="text-sm">Upload files to get started.</p>
      </div>
    );
  }

  /* Folders first, then alphabetical */
  const sorted = [...entries].sort((a, b) => {
    if (a.type === 'folder' && b.type !== 'folder') return -1;
    if (a.type !== 'folder' && b.type === 'folder') return 1;
    return (a.name ?? '').localeCompare(b.name ?? '');
  });

  return (
    <>
      {/* Header row */}
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-xl font-semibold">{title}</h2>
        <div className="flex gap-1">
          <button
            onClick={() => setView('grid')}
            className={`p-1.5 rounded border text-sm transition ${
              view === 'grid'
                ? 'bg-[#0078d4] text-white border-[#0078d4]'
                : 'bg-white text-gray-500 border-gray-200 hover:bg-gray-50'
            }`}
          >
            <FiGrid />
          </button>
          <button
            onClick={() => setView('list')}
            className={`p-1.5 rounded border text-sm transition ${
              view === 'list'
                ? 'bg-[#0078d4] text-white border-[#0078d4]'
                : 'bg-white text-gray-500 border-gray-200 hover:bg-gray-50'
            }`}
          >
            <FiList />
          </button>
        </div>
      </div>

      {/* Grid view */}
      {view === 'grid' ? (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(140px,1fr))] gap-3">
          {sorted.map((entry) => (
            <FileCard
              key={entry.name}
              entry={entry}
              onClick={entry.type === 'folder' ? onNavigate : onDownload}
            />
          ))}
        </div>
      ) : (
        /* Table view */
        <table className="w-full border-collapse">
          <thead>
            <tr className="text-left text-xs text-gray-500 font-semibold">
              <th className="py-2.5 px-3 border-b border-gray-200">Name</th>
              <th className="py-2.5 px-3 border-b border-gray-200">Size</th>
              <th className="py-2.5 px-3 border-b border-gray-200 w-20">Actions</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((entry) => {
              const Icon = iconFor(entry);
              const isFolder = entry.type === 'folder';
              return (
                <tr key={entry.name} className="hover:bg-gray-50 text-[13px]">
                  <td className="py-2.5 px-3 border-b border-gray-100">
                    <span
                      className="flex items-center gap-2 cursor-pointer"
                      onClick={() => (isFolder ? onNavigate(entry) : onDownload(entry))}
                    >
                      <Icon className="text-xl" style={{ color: isFolder ? '#dcb67a' : '#0078d4' }} />
                      {entry.name}
                    </span>
                  </td>
                  <td className="py-2.5 px-3 border-b border-gray-100">
                    {isFolder ? '—' : formatSize(entry.size)}
                  </td>
                  <td className="py-2.5 px-3 border-b border-gray-100">
                    <div className="flex gap-1">
                      {!isFolder && (
                        <button
                          title="Download"
                          onClick={() => onDownload(entry)}
                          className="p-1 rounded text-gray-400 hover:text-[#0078d4] hover:bg-gray-100 transition"
                        >
                          <FiDownload />
                        </button>
                      )}
                      <button
                        title="Delete"
                        onClick={() => onDelete(entry)}
                        className="p-1 rounded text-gray-400 hover:text-red-600 hover:bg-gray-100 transition"
                      >
                        <FiTrash2 />
                      </button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </>
  );
}
