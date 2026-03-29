/**
 * CloudBox — Fault Tolerance page.
 *
 * Displays live cluster fault-tolerance status: node health, MTTF/MTTR/
 * availability metrics, active recovery tasks with progress bars, and
 * recent failures. Polls every 5 seconds.
 *
 * Lecture 4 (Fault Tolerance): heartbeat monitoring, failure detection,
 * self-healing recovery, MTTF/MTTR, Five-Nines availability.
 */

import { useState, useEffect } from 'react';
import { getFaultStatus } from '../services/api.js';

const POLL_MS = 5000;

const STATE_COLORS = {
  HEALTHY:     'bg-green-100 text-green-800',
  DEGRADED:    'bg-yellow-100 text-yellow-800',
  CRITICAL:    'bg-red-100 text-red-800',
  PARTITIONED: 'bg-purple-100 text-purple-800',
};

const STATUS_COLORS = {
  HEALTHY:    'text-green-600',
  RECOVERING: 'text-yellow-600',
  UNHEALTHY:  'text-red-600',
  UNKNOWN:    'text-gray-400',
};

function timeAgo(iso) {
  if (!iso) return 'never';
  const diff = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (diff < 60)   return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  return `${Math.floor(diff / 3600)}h ago`;
}

function formatSeconds(secs) {
  if (secs == null || secs < 0) return '—';
  if (secs < 60)   return `${Math.round(secs)}s`;
  if (secs < 3600) return `${Math.round(secs / 60)}m`;
  return `${(secs / 3600).toFixed(1)}h`;
}

export default function FaultTolerancePage() {
  const [status, setStatus] = useState(null);
  const [error, setError]   = useState(null);

  useEffect(() => {
    async function load() {
      try {
        const data = await getFaultStatus();
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
    return <div className="p-6 text-gray-500">Loading fault tolerance status…</div>;
  }

  const nodes = Object.values(status.nodeHealthMap || {})
    .sort((a, b) => {
      const idA = parseInt(a.nodeId?.replace('node-', '') || '0');
      const idB = parseInt(b.nodeId?.replace('node-', '') || '0');
      return idA - idB;
    });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold text-gray-800">Fault Tolerance</h1>

      {/* ── Summary cards ─────────────────────────────────────── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wide">Cluster State</p>
          <span
            className={`mt-1 inline-block px-2 py-0.5 rounded text-sm font-semibold
              ${STATE_COLORS[status.clusterState] ?? 'bg-gray-100 text-gray-700'}`}
          >
            {status.clusterState}
          </span>
        </div>

        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wide">Quorum</p>
          <p className={`text-xl font-bold mt-1 ${status.hasQuorum ? 'text-green-600' : 'text-red-600'}`}>
            {status.hasQuorum ? 'Established' : 'Lost'}
          </p>
        </div>

        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wide">Nodes</p>
          <p className="text-xl font-bold mt-1 text-gray-800">
            {status.healthyNodes}/{status.totalNodes} healthy
          </p>
          {status.recoveringNodes > 0 && (
            <p className="text-xs text-yellow-600 mt-0.5">{status.recoveringNodes} recovering</p>
          )}
        </div>

        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-xs text-gray-500 uppercase tracking-wide">Under-replicated Files</p>
          <p className={`text-xl font-bold mt-1 ${status.underReplicatedFileCount > 0 ? 'text-red-600' : 'text-green-600'}`}>
            {status.underReplicatedFileCount}
          </p>
        </div>
      </div>

      {/* ── Reliability metrics (MTTF / MTTR / Availability) ──── */}
      <div className="bg-white rounded-lg shadow p-5">
        <h2 className="text-base font-semibold text-gray-700 mb-4">Reliability Metrics</h2>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
          <div>
            <p className="text-xs text-gray-500 uppercase tracking-wide">Availability</p>
            <p className="text-2xl font-bold text-blue-600">
              {status.availabilityPercentage?.toFixed(3)}%
            </p>
            <p className="text-xs text-gray-400 mt-0.5">{status.availabilityLabel}</p>
          </div>
          <div>
            <p className="text-xs text-gray-500 uppercase tracking-wide">MTTF</p>
            <p className="text-2xl font-bold text-gray-800">{formatSeconds(status.mttfSeconds)}</p>
            <p className="text-xs text-gray-400 mt-0.5">Mean Time To Failure</p>
          </div>
          <div>
            <p className="text-xs text-gray-500 uppercase tracking-wide">MTTR</p>
            <p className="text-2xl font-bold text-gray-800">{formatSeconds(status.mttrSeconds)}</p>
            <p className="text-xs text-gray-400 mt-0.5">Mean Time To Repair</p>
          </div>
          <div>
            <p className="text-xs text-gray-500 uppercase tracking-wide">Avg Replication</p>
            <p className="text-2xl font-bold text-gray-800">
              {status.averageReplicationFactor?.toFixed(1)}
            </p>
            <p className="text-xs text-gray-400 mt-0.5">of 5 replicas</p>
          </div>
        </div>
      </div>

      {/* ── Node health table ──────────────────────────────────── */}
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-200">
          <h2 className="text-base font-semibold text-gray-700">Node Health</h2>
        </div>
        <table className="min-w-full divide-y divide-gray-200 text-sm">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-5 py-3 text-left font-medium text-gray-500">Node</th>
              <th className="px-5 py-3 text-left font-medium text-gray-500">Status</th>
              <th className="px-5 py-3 text-left font-medium text-gray-500">Last Heartbeat</th>
              <th className="px-5 py-3 text-left font-medium text-gray-500">Missed Beats</th>
              <th className="px-5 py-3 text-left font-medium text-gray-500">Replicas</th>
              <th className="px-5 py-3 text-left font-medium text-gray-500">Failure Reason</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {nodes.map((n) => (
              <tr key={n.nodeId} className="hover:bg-gray-50">
                <td className="px-5 py-3 font-mono font-medium text-gray-800">{n.nodeId}</td>
                <td className={`px-5 py-3 font-semibold ${STATUS_COLORS[n.status] ?? 'text-gray-600'}`}>
                  {n.status}
                </td>
                <td className="px-5 py-3 text-gray-600">{timeAgo(n.lastHeartbeat)}</td>
                <td className="px-5 py-3 text-gray-600">{n.missedHeartbeats}</td>
                <td className="px-5 py-3 text-gray-600">
                  {n.healthyReplicaCount}/{n.replicationFactor}
                </td>
                <td className="px-5 py-3 text-gray-500 italic">{n.failureReason ?? '—'}</td>
              </tr>
            ))}
            {nodes.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-6 text-center text-gray-400">
                  No node health data available
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* ── Active recovery tasks ──────────────────────────────── */}
      {status.activeRecoveryTasks?.length > 0 && (
        <div className="bg-white rounded-lg shadow p-5">
          <h2 className="text-base font-semibold text-gray-700 mb-4">
            Active Recovery Tasks ({status.activeRecoveryTasks.length})
          </h2>
          <div className="space-y-4">
            {status.activeRecoveryTasks.map((t) => (
              <div key={t.recoveryId} className="p-3 rounded-lg border border-gray-200">
                <div className="flex justify-between items-center mb-2">
                  <span className="font-medium text-gray-800">{t.failedNodeId}</span>
                  <span className="text-xs px-2 py-0.5 rounded bg-blue-100 text-blue-700">
                    {t.status}
                  </span>
                </div>
                <div className="w-full bg-gray-200 rounded-full h-2">
                  <div
                    className="bg-blue-500 h-2 rounded-full transition-all duration-500"
                    style={{ width: `${t.progressPercentage ?? 0}%` }}
                  />
                </div>
                <p className="text-xs text-gray-500 mt-1">
                  {(t.progressPercentage ?? 0).toFixed(1)}% — source: {t.sourceNodeId}
                </p>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Recent failures ────────────────────────────────────── */}
      {status.recentFailures?.length > 0 && (
        <div className="bg-white rounded-lg shadow p-5">
          <h2 className="text-base font-semibold text-gray-700 mb-3">Recent Failures</h2>
          <ul className="space-y-1">
            {status.recentFailures.map((f, i) => (
              <li key={i} className="text-sm text-red-600">• {f}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
