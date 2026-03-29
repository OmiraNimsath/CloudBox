/**
 * CloudBox — Time Synchronization page.
 *
 * Displays live clock synchronization status: HLC state, Lamport timestamp,
 * NTP offset, per-node clock skew with alert indicators, and sync health.
 * Polls every 6 seconds.
 *
 * Lecture 5 (Time & Ordering): Cristian's Algorithm, Hybrid Logical Clocks,
 * Lamport timestamps, clock skew detection, happens-before ordering.
 */

import { useState, useEffect } from 'react';
import { getTimeSyncStatus, getSkewReport } from '../services/api.js';

const POLL_MS = 6000;

function msAgo(ts) {
  if (!ts) return '—';
  const diff = Date.now() - ts;
  if (diff < 1000)  return 'just now';
  if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
  return `${Math.floor(diff / 60000)}m ago`;
}

export default function TimeSyncPage() {
  const [status, setStatus] = useState(null);
  const [report, setReport] = useState(null);
  const [error, setError]   = useState(null);

  useEffect(() => {
    let active = true;
    const poll = async () => {
      try {
        const [s, r] = await Promise.all([getTimeSyncStatus(), getSkewReport()]);
        if (active) { setStatus(s); setReport(r); setError(null); }
      } catch (e) {
        if (active) setError(e.message || 'Failed to fetch time sync data');
      }
    };
    poll();
    const id = setInterval(poll, POLL_MS);
    return () => { active = false; clearInterval(id); };
  }, []);

  if (error) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-bold mb-4">Time Synchronization</h1>
        <div className="bg-red-50 border border-red-200 rounded-lg p-4 text-red-700">{error}</div>
      </div>
    );
  }

  if (!status || !report) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-bold mb-4">Time Synchronization</h1>
        <p className="text-gray-500">Loading…</p>
      </div>
    );
  }

  const synced = status.synced;
  const syncBadge = synced
    ? 'bg-green-100 text-green-800'
    : 'bg-red-100 text-red-800';

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-2xl font-bold">Time Synchronization</h1>

      {/* ── Summary cards ────────────────────────────────── */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Card label="Sync Health">
          <span className={`px-2 py-0.5 rounded text-sm font-semibold ${syncBadge}`}>
            {synced ? 'IN SYNC' : 'SKEW ALERT'}
          </span>
        </Card>
        <Card label="Max Clock Skew">
          <span className="text-lg font-mono">{status.currentMaxClockSkew ?? 0} ms</span>
          <div className="text-xs text-gray-400 mt-1">Peak: {status.maxClockSkew ?? 0} ms</div>
        </Card>
        <Card label="Synced Nodes">
          <span className="text-lg font-mono">{status.syncedNodeCount ?? 0} / {status.totalNodes ?? report.totalNodes ?? 5}</span>
        </Card>
        <Card label="Skew Threshold">
          <span className="text-lg font-mono">{report.threshold ?? 100} ms</span>
        </Card>
      </div>

      {/* ── Clock state ──────────────────────────────────── */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card label="Hybrid Logical Clock">
          <div className="space-y-1 text-sm font-mono">
            <div>Physical: <span className="font-semibold">{status.hlcPhysicalTime}</span></div>
            <div>Logical counter: <span className="font-semibold">{status.hlcLogicalCounter}</span></div>
          </div>
        </Card>
        <Card label="Lamport Timestamp">
          <span className="text-2xl font-mono font-bold">{status.logicalTimestamp}</span>
        </Card>
        <Card label="NTP Offset">
          <span className="text-lg font-mono">{typeof status.offset === 'number' ? `${status.offset.toFixed(3)} s` : '—'}</span>
          <div className="text-xs text-gray-500 mt-1">Last sync: {msAgo(status.lastSync)}</div>
        </Card>
      </div>

      {/* ── Per-node skew table ──────────────────────────── */}
      <div className="bg-white rounded-lg shadow overflow-hidden">
        <div className="px-4 py-3 border-b font-semibold text-gray-700">Per-Node Clock Skew</div>

        {report.skewDetails && report.skewDetails.length > 0 ? (
          <table className="w-full text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-4 py-2 text-left">Node</th>
                <th className="px-4 py-2 text-center">Status</th>
                <th className="px-4 py-2 text-right">Skew</th>
                <th className="px-4 py-2 text-right">Max Skew</th>
                <th className="px-4 py-2 text-center">Alert</th>
                <th className="px-4 py-2 text-right">Measured</th>
              </tr>
            </thead>
            <tbody>
              {report.skewDetails.map((n) => {
                const alert = n.alertTriggered;
                const nodeStatus = n.nodeStatus ?? 'HEALTHY';
                const isDown = nodeStatus === 'FAILED' || nodeStatus === 'UNREACHABLE';
                return (
                  <tr key={n.nodeId} className={`border-t ${isDown ? 'bg-gray-50 opacity-60' : 'hover:bg-gray-50'}`}>
                    <td className="px-4 py-2 font-mono">Node {n.nodeId}</td>
                    <td className="px-4 py-2 text-center">
                      {nodeStatus === 'FAILED'
                        ? <span className="px-1.5 py-0.5 rounded text-xs font-semibold bg-red-100 text-red-700">FAILED</span>
                        : nodeStatus === 'UNREACHABLE'
                          ? <span className="px-1.5 py-0.5 rounded text-xs font-semibold bg-gray-200 text-gray-600">UNREACHABLE</span>
                          : <span className="px-1.5 py-0.5 rounded text-xs font-semibold bg-green-100 text-green-700">HEALTHY</span>
                      }
                    </td>
                    <td className={`px-4 py-2 text-right font-mono ${!isDown && alert ? 'text-red-600 font-bold' : 'text-gray-400'}`}>
                      {isDown ? '—' : `${n.skewMillis > 0 ? '+' : ''}${n.skewMillis} ms`}
                    </td>
                    <td className="px-4 py-2 text-right font-mono text-gray-400">
                      {isDown ? '—' : `${n.maxSkewMillis} ms`}
                    </td>
                    <td className="px-4 py-2 text-center">
                      {isDown
                        ? <span className="inline-block w-3 h-3 rounded-full bg-gray-300" title="Node down" />
                        : alert
                          ? <span className="inline-block w-3 h-3 rounded-full bg-red-500" title="Skew exceeds threshold" />
                          : <span className="inline-block w-3 h-3 rounded-full bg-green-500" title="Within threshold" />
                      }
                    </td>
                    <td className="px-4 py-2 text-right text-gray-500">
                      {isDown && !n.lastMeasuredAt ? '—' : msAgo(n.lastMeasuredAt)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : (
          <p className="px-4 py-6 text-gray-400 text-center">No skew measurements yet — waiting for detection cycle…</p>
        )}
      </div>

      {/* ── Algorithm info ───────────────────────────────── */}
      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4 text-sm text-blue-800">
        <strong>Cristian's Algorithm</strong> — This node periodically queries a reference node's clock,
        measures the round-trip time (RTT), and estimates the offset as{' '}
        <code className="bg-blue-100 px-1 rounded">T₁ − (T_server + RTT/2)</code>.
        The Hybrid Logical Clock (HLC) preserves causal ordering across nodes while staying
        close to physical time.
      </div>
    </div>
  );
}

function Card({ label, children }) {
  return (
    <div className="bg-white rounded-lg shadow p-4">
      <div className="text-xs text-gray-500 uppercase tracking-wide mb-1">{label}</div>
      {children}
    </div>
  );
}
