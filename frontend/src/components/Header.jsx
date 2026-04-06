import { FiUploadCloud } from 'react-icons/fi';

export default function Header({ clusterInfo, onUploadClick }) {
  const aliveCount = clusterInfo?.healthyNodes ?? 0;
  const totalCount = clusterInfo?.totalNodes ?? 5;
  const state = clusterInfo?.clusterState;

  const pillStyle = !clusterInfo
    ? 'bg-gray-100 text-gray-500'
    : state === 'HEALTHY'
      ? 'bg-green-100 text-green-700'
      : state === 'DEGRADED'
        ? 'bg-yellow-100 text-yellow-700'
        : 'bg-red-100 text-red-700';

  const dotBase = !clusterInfo
    ? 'bg-gray-400'
    : state === 'HEALTHY' ? 'bg-green-500'
    : state === 'DEGRADED' ? 'bg-yellow-500'
    : 'bg-red-500';

  const dotPing = !clusterInfo
    ? ''
    : state === 'HEALTHY' ? 'bg-green-400'
    : state === 'DEGRADED' ? 'bg-yellow-400'
    : 'bg-red-400';

  return (
    <header className="h-14 bg-white border-b border-gray-200 flex items-center justify-end px-6 gap-3 shrink-0">
      {/* Cluster health pill */}
      <div className={`flex items-center gap-2 text-sm font-semibold px-3 py-1.5 rounded-full select-none ${pillStyle}`}>
        <span className="relative flex h-2 w-2">
          {clusterInfo && (
            <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${dotPing}`} />
          )}
          <span className={`relative inline-flex rounded-full h-2 w-2 ${dotBase}`} />
        </span>
        {clusterInfo ? `${aliveCount}/${totalCount} nodes · ${state}` : 'Connecting…'}
      </div>

      {/* Upload */}
      <button
        onClick={onUploadClick}
        className="flex items-center gap-2 text-sm font-semibold bg-[#0078d4] hover:bg-[#106ebe] text-white px-4 py-2 rounded-lg transition shadow-sm"
      >
        <FiUploadCloud size={15} />
        Upload
      </button>
    </header>
  );
}
