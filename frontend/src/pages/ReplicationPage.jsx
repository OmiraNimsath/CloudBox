/**
 * CloudBox — Replication page.
 *
 * Displays replication factor, quorum size, consistency model, and per-file
 * replica distribution across the 5-node cluster. Polls every 8 seconds.
 *
 * Lecture 6 (Replication): quorum-based replication (W + R > N),
 * consistency models, replica placement, under-replication detection.
 */

import { useState, useEffect } from 'react';
import { getReplicationStatus } from '../services/api.js';

const POLL_MS = 8000;

export default function ReplicationPage() {
  const [status, setStatus] = useState(null);
  const [error, setError]   = useState(null);

  useEffect(() => {
    async function load() {
      try {
        const data = await getReplicationStatus();
        setStatus(data);
        setError(null);
      } catch {
        setError('Backend unreachable — retrying…');
      }
    }
    load();
    const id = setInterval(load, POLL_MS);
    return () => clearInterval(id);
  }, []);

  if (error && !status) {
    return <div className="p-6 text-red-500">{error}</div>;
  }
  if (!status) {
    return <div className="p-6 text-gray-500">Loading replication status…</div>;
  }

  const { replicationFactor, quorumSize, totalFiles, fullyReplicatedFiles, underReplicatedFiles, consistencyModel, files } = status;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-800">Replication</h1>

      {/* ── Summary cards ─────────────────────────────────────── */}
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        <Card label="Replication Factor" value={replicationFactor} />
        <Card label="Quorum Size" value={quorumSize} sub={`W + R > ${replicationFactor}`} />
        <Card label="Total Files" value={totalFiles} />
        <Card
          label="Fully Replicated"
          value={fullyReplicatedFiles}
          color={fullyReplicatedFiles === totalFiles ? 'text-green-600' : 'text-yellow-600'}
        />
        <Card
          label="Under-replicated"
          value={underReplicatedFiles}
          color={underReplicatedFiles > 0 ? 'text-red-600' : 'text-green-600'}
        />
      </div>

      {/* ── Consistency model ──────────────────────────────────── */}
      <div className="bg-white rounded-lg shadow p-5">
        <h2 className="text-base font-semibold text-gray-700 mb-2">Consistency Model</h2>
        <span className="inline-block px-3 py-1 rounded bg-blue-100 text-blue-800 text-sm font-semibold">
          {consistencyModel?.replace(/_/g, ' ')}
        </span>
        <p className="text-xs text-gray-400 mt-2">
          Writes require {quorumSize} ACKs out of {replicationFactor} replicas before acknowledging the client.
        </p>
      </div>

      {/* ── Per-file replica table ─────────────────────────────── */}
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-200">
          <h2 className="text-base font-semibold text-gray-700">File Replica Distribution</h2>
        </div>
        {files?.length > 0 ? (
          <table className="min-w-full divide-y divide-gray-200 text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-5 py-3 text-left font-medium text-gray-500">File</th>
                <th className="px-5 py-3 text-left font-medium text-gray-500">Size</th>
                <th className="px-5 py-3 text-left font-medium text-gray-500">Replicas</th>
                <th className="px-5 py-3 text-left font-medium text-gray-500">Present On</th>
                <th className="px-5 py-3 text-left font-medium text-gray-500">Status</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {files.map((f) => (
                <tr key={f.fileName} className="hover:bg-gray-50">
                  <td className="px-5 py-3 font-mono text-gray-800">{f.fileName}</td>
                  <td className="px-5 py-3 text-gray-600">{formatBytes(f.size)}</td>
                  <td className="px-5 py-3 text-gray-700 font-semibold">
                    {f.replicaCount}/{f.expectedReplicas}
                  </td>
                  <td className="px-5 py-3">
                    <div className="flex gap-1">
                      {[1, 2, 3, 4, 5].map((n) => (
                        <span
                          key={n}
                          className={`w-6 h-6 flex items-center justify-center rounded text-xs font-bold
                            ${f.presentOnNodes?.includes(n)
                              ? 'bg-green-100 text-green-700'
                              : 'bg-red-100 text-red-500'}`}
                        >
                          {n}
                        </span>
                      ))}
                    </div>
                  </td>
                  <td className="px-5 py-3">
                    {f.fullyReplicated ? (
                      <span className="text-green-600 font-semibold text-xs">OK</span>
                    ) : (
                      <span className="text-red-600 font-semibold text-xs">UNDER-REPLICATED</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="px-5 py-8 text-center text-gray-400">
            No files in the cluster yet — upload a file to see replica distribution.
          </div>
        )}
      </div>
    </div>
  );
}

function Card({ label, value, color = 'text-gray-800', sub }) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <p className="text-xs text-gray-500 uppercase tracking-wide">{label}</p>
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
