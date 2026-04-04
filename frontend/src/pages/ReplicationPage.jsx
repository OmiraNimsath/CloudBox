import { useState, useEffect } from 'react';
import { FiDatabase, FiLayers } from 'react-icons/fi';
import { getReplicationStatus } from '../services/api.js';

const POLL_MS = 8000;

export default function ReplicationPage() {
  const [status, setStatus] = useState(null);
  const [error, setError]   = useState(null);

  useEffect(() => {
    async function load() {
      try { const data = await getReplicationStatus(); setStatus(data); setError(null); }
      catch { setError('Backend unreachable â€” retryingâ€¦'); }
    }
    load();
    const id = setInterval(load, POLL_MS);
    return () => clearInterval(id);
  }, []);

  if (error && !status) return <div className="p-6 text-red-500">{error}</div>;
  if (!status) return <div className="p-6 text-gray-400">Loading replication statusâ€¦</div>;

  const { replicationFactor, quorumSize, totalFiles, fullyReplicatedFiles, underReplicatedFiles, consistencyModel, files } = status;

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Replication</h1>
        <p className="text-sm text-gray-400 mt-0.5">Quorum-based replica distribution across all nodes</p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        <StatCard label="Replication Factor" value={replicationFactor} />
        <StatCard label="Quorum Size" value={quorumSize} sub={`W + R > ${replicationFactor}`} />
        <StatCard label="Total Files" value={totalFiles} />
        <StatCard label="Fully Replicated" value={fullyReplicatedFiles}
          color={fullyReplicatedFiles === totalFiles ? 'text-green-600' : 'text-yellow-600'} />
        <StatCard label="Under-replicated" value={underReplicatedFiles}
          color={underReplicatedFiles > 0 ? 'text-red-600' : 'text-green-600'} />
      </div>

      {/* Consistency model */}
      <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-4">
          <div className="w-8 h-8 bg-blue-50 rounded-lg flex items-center justify-center">
            <FiLayers size={15} className="text-blue-600" />
          </div>
          <h2 className="text-base font-semibold text-gray-800">Consistency Model</h2>
        </div>
        <div className="flex items-center gap-3">
          <span className="inline-block px-3 py-1.5 rounded-lg bg-[#0078d4]/10 text-[#0078d4] text-sm font-bold">
            {consistencyModel?.replace(/_/g, ' ')}
          </span>
          <span className="text-sm text-gray-500">
            Writes require {quorumSize} ACKs out of {replicationFactor} replicas.
          </span>
        </div>
      </div>

      {/* Per-file table */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-gray-50 rounded-lg flex items-center justify-center">
              <FiDatabase size={15} className="text-gray-500" />
            </div>
            <h2 className="text-base font-semibold text-gray-800">File Replica Distribution</h2>
          </div>
        </div>
        {files?.length > 0 ? (
          <table className="min-w-full divide-y divide-gray-100 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {['File', 'Size', 'Replicas', 'Present On', 'Status'].map(h => (
                  <th key={h} className="px-5 py-3 text-left font-medium text-gray-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {files.map((f) => (
                <tr key={f.name} className="hover:bg-gray-50 transition-colors">
                  <td className="px-5 py-3 font-medium text-gray-800">{f.name}</td>
                  <td className="px-5 py-3 text-gray-500">{formatBytes(f.sizeBytes)}</td>
                  <td className="px-5 py-3">
                    <span className={`font-bold ${
                      f.replicaCount === f.expectedReplicas ? 'text-green-600' : 'text-yellow-600'
                    }`}>{f.replicaCount}</span>
                    <span className="text-gray-400">/{f.expectedReplicas}</span>
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex gap-1">
                      {[1, 2, 3, 4, 5].map((n) => (
                        <span key={n}
                          className={`w-7 h-7 flex items-center justify-center rounded-lg text-xs font-bold
                            ${f.presentOnNodes?.includes(n)
                              ? 'bg-green-100 text-green-700'
                              : 'bg-red-50 text-red-400'}`}>
                          {n}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-5 py-3">
                    {f.fullyReplicated
                      ? <span className="inline-block px-2 py-0.5 rounded-md text-xs font-semibold bg-green-100 text-green-700">OK</span>
                      : <span className="inline-block px-2 py-0.5 rounded-md text-xs font-semibold bg-red-100 text-red-600">UNDER-REPLICATED</span>
                    }
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="px-5 py-12 text-center text-gray-400">
            No files in the cluster yet, upload a file to see replica distribution.
          </div>
        )}
      </div>
    </div>
  );
}

function StatCard({ label, value, color = 'text-gray-800', sub }) {
  return (
    <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
      <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${color}`}>{value}</p>
      {sub && <p className="text-xs text-gray-400 mt-0.5">{sub}</p>}
    </div>
  );
}

function formatBytes(bytes) {
  if (bytes == null || bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return `${(bytes / Math.pow(k, i)).toFixed(1)} ${sizes[i]}`;
}
