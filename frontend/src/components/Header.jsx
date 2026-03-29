/**
 * CloudBox — Header component (OneDrive-style top bar).
 *
 * Logo, search placeholder, cluster health dot, and Upload button.
 */

import { FiCloud, FiUploadCloud } from 'react-icons/fi';

export default function Header({ clusterInfo, onUploadClick }) {
  const aliveCount = clusterInfo?.alive ?? 0;
  const totalCount = clusterInfo?.total ?? 0;

  const dotColor =
    aliveCount === totalCount && totalCount > 0
      ? 'bg-green-500'
      : aliveCount >= Math.ceil(totalCount / 2)
        ? 'bg-yellow-400'
        : 'bg-red-500';

  const clusterLabel = clusterInfo
    ? `${aliveCount}/${totalCount} nodes`
    : 'connecting…';

  return (
    <header className="flex items-center justify-between h-12 px-4 bg-[#0078d4] text-white select-none">
      {/* Left — logo */}
      <div className="flex items-center gap-3 text-base font-semibold">
        <FiCloud className="text-xl" />
        <span>CloudBox</span>
      </div>

      {/* Right — cluster + upload */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2 text-xs text-white/80">
          <span className={`w-2 h-2 rounded-full ${dotColor}`} />
          <span>{clusterLabel}</span>
        </div>

        <button
          onClick={onUploadClick}
          className="flex items-center gap-1.5 text-xs font-semibold bg-white/15 hover:bg-white/25 border border-white/30 rounded px-3 py-1 transition"
        >
          <FiUploadCloud /> Upload
        </button>
      </div>
    </header>
  );
}
