import {
  FiFile, FiFileText, FiImage, FiMusic,
  FiFilm, FiCode, FiArchive, FiDownload, FiTrash2,
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

const EXT_COLORS = {
  pdf: 'bg-red-100 text-red-500',
  doc: 'bg-blue-100 text-blue-600', docx: 'bg-blue-100 text-blue-600',
  txt: 'bg-gray-100 text-gray-500', md: 'bg-gray-100 text-gray-500',
  png: 'bg-purple-100 text-purple-500', jpg: 'bg-purple-100 text-purple-500',
  jpeg: 'bg-purple-100 text-purple-500', gif: 'bg-pink-100 text-pink-500',
  svg: 'bg-orange-100 text-orange-500', webp: 'bg-purple-100 text-purple-500',
  mp3: 'bg-green-100 text-green-600', wav: 'bg-green-100 text-green-600', flac: 'bg-green-100 text-green-600',
  mp4: 'bg-yellow-100 text-yellow-600', avi: 'bg-yellow-100 text-yellow-600',
  mov: 'bg-yellow-100 text-yellow-600', mkv: 'bg-yellow-100 text-yellow-600',
  js: 'bg-yellow-100 text-yellow-600', jsx: 'bg-cyan-100 text-cyan-600',
  ts: 'bg-blue-100 text-blue-600', tsx: 'bg-blue-100 text-blue-600',
  py: 'bg-blue-100 text-blue-700', java: 'bg-orange-100 text-orange-600',
  css: 'bg-blue-100 text-blue-500', html: 'bg-orange-100 text-orange-500',
  json: 'bg-gray-100 text-gray-600',
  zip: 'bg-amber-100 text-amber-600', tar: 'bg-amber-100 text-amber-600',
  gz: 'bg-amber-100 text-amber-600', rar: 'bg-amber-100 text-amber-600',
};

export function iconFor(entry) {
  const ext = (entry.name ?? '').split('.').pop().toLowerCase();
  return EXT_ICONS[ext] || FiFile;
}

export function colorFor(entry) {
  const ext = (entry.name ?? '').split('.').pop().toLowerCase();
  return EXT_COLORS[ext] || 'bg-gray-100 text-gray-500';
}

export function formatSize(bytes) {
  if (bytes == null || bytes === 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function FileCard({ entry, onDownload, onDelete }) {
  const Icon = iconFor(entry);
  const colorClass = colorFor(entry);
  const ext = (entry.name ?? '').split('.').pop().toUpperCase();

  return (
    <div
      onClick={() => onDownload(entry)}
      title={entry.name}
      className="group relative flex flex-col bg-white border border-gray-100 rounded-xl p-4 cursor-pointer
                 hover:border-[#0078d4] hover:shadow-md transition-all duration-150"
    >
      {/* Coloured icon area */}
      <div className={`w-12 h-12 rounded-xl flex items-center justify-center mb-3 ${colorClass}`}>
        <Icon size={22} />
      </div>

      {/* File name */}
      <span className="text-[13px] font-medium text-gray-800 max-w-full overflow-hidden text-ellipsis whitespace-nowrap">
        {entry.name}
      </span>

      {/* Meta row */}
      <div className="flex items-center justify-between mt-1.5">
        <span className="text-[10px] font-semibold text-gray-400 uppercase tracking-wide">{ext}</span>
        {entry.sizeBytes != null && (
          <span className="text-[11px] text-gray-400">{formatSize(entry.sizeBytes)}</span>
        )}
      </div>

      {/* Berkeley-corrected upload time + Lamport timestamp */}
      {entry.uploadedAtMs > 0 && (
        <div className="mt-1.5 flex items-center justify-between gap-1">
          <span className="text-[10px] text-gray-400 font-mono">
            {(() => {
              const d = new Date(entry.uploadedAtMs);
              const hh = String(d.getHours()).padStart(2, '0');
              const mm = String(d.getMinutes()).padStart(2, '0');
              const ss = String(d.getSeconds()).padStart(2, '0');
              const ms = String(d.getMilliseconds()).padStart(3, '0');
              return `${hh}:${mm}:${ss}.${ms}`;
            })()}
          </span>
          <span className="text-[10px] font-mono text-purple-400" title="Lamport logical timestamp">
            L:{entry.logicalTimestamp ?? 0}
          </span>
        </div>
      )}

      {/* Hover overlay */}
      <div
        className="absolute inset-0 flex items-center justify-center gap-2 bg-white/90 rounded-xl
                   opacity-0 group-hover:opacity-100 transition-opacity duration-150"
        onClick={(e) => e.stopPropagation()}
      >
        <button
          title="Download"
          onClick={() => onDownload(entry)}
          className="p-2.5 rounded-xl bg-[#0078d4] text-white hover:bg-[#106ebe] transition shadow-sm"
        >
          <FiDownload size={15} />
        </button>
        <button
          title="Delete"
          onClick={() => onDelete(entry)}
          className="p-2.5 rounded-xl bg-red-500 text-white hover:bg-red-600 transition shadow-sm"
        >
          <FiTrash2 size={15} />
        </button>
      </div>
    </div>
  );
}
