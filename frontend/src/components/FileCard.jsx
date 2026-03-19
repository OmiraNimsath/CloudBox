/**
 * CloudBox — FileCard component (grid item).
 *
 * Renders a single file or folder as a card with an icon, name, and size.
 */

import {
  FiFolder, FiFile, FiFileText, FiImage, FiMusic,
  FiFilm, FiCode, FiArchive,
} from 'react-icons/fi';

const EXT_ICONS = {
  txt: FiFileText, md: FiFileText, pdf: FiFileText, doc: FiFileText, docx: FiFileText,
  png: FiImage, jpg: FiImage, jpeg: FiImage, gif: FiImage, svg: FiImage, webp: FiImage,
  mp3: FiMusic, wav: FiMusic, flac: FiMusic,
  mp4: FiFilm, avi: FiFilm, mov: FiFilm, mkv: FiFilm,
  js: FiCode, jsx: FiCode, ts: FiCode, tsx: FiCode, py: FiCode, java: FiCode,
  css: FiCode, html: FiCode, json: FiCode,
  zip: FiArchive, tar: FiArchive, gz: FiArchive, rar: FiArchive,
};

function iconFor(entry) {
  if (entry.type === 'folder') return FiFolder;
  const ext = (entry.name ?? '').split('.').pop().toLowerCase();
  return EXT_ICONS[ext] || FiFile;
}

export function formatSize(bytes) {
  if (bytes == null) return '';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export { iconFor };

export default function FileCard({ entry, onClick }) {
  const Icon = iconFor(entry);
  const isFolder = entry.type === 'folder';

  return (
    <div
      onClick={() => onClick(entry)}
      title={entry.name}
      className="flex flex-col items-center p-4 bg-white border border-gray-200 rounded-md cursor-pointer
                 hover:border-[#0078d4] hover:shadow-sm transition text-center"
    >
      <Icon
        className="text-4xl mb-2"
        style={{ color: isFolder ? '#dcb67a' : '#0078d4' }}
      />
      <span className="text-[13px] max-w-[120px] overflow-hidden text-ellipsis whitespace-nowrap">
        {entry.name}
      </span>
      {!isFolder && entry.size != null && (
        <span className="text-[11px] text-gray-400 mt-1">{formatSize(entry.size)}</span>
      )}
    </div>
  );
}
