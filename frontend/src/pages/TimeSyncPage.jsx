/**
 * CloudBox — Time Synchronization page (Berkeley Algorithm).
 */

import { useState, useEffect } from 'react';
import { FiClock, FiActivity, FiStar } from 'react-icons/fi';
import { getTimeSyncStatus, getTimeSyncStatusFromNode, getSkewReport } from '../services/api.js';

const POLL_MS = 1000;

function msAgo(ts) {
  if (!ts) return '-';
  const diff = Date.now() - ts;
  if (diff < 1000)  return 'just now';
  if (diff < 60000) return `${Math.floor(diff / 1000)}s ago`;
  return `${Math.floor(diff / 60000)}m ago`;
}

export default function TimeSyncPage() {
  const [status, setStatus] = useState(null);
  const [masterStatus, setMasterStatus] = useState(null);
  const [report, setReport] = useState(null);
  const [error, setError]   = useState(null);

  useEffect(() => {
    let active = true;
    const poll = async () => {
      try {
        const [s, r] = await Promise.all([getTimeSyncStatus(), getSkewReport()]);
        // Fetch master's status directly so Last δ and Accumulated correction
        // always reflect the master node's own values, regardless of which node
        // the frontend is currently connected to.
        let ms = s;
        if (s.berkeleyMasterNodeId && s.berkeleyMasterNodeId !== s.nodeId) {
          try { ms = await getTimeSyncStatusFromNode(s.berkeleyMasterNodeId); } catch { /* keep s */ }
        }
        if (active) { setStatus(s); setMasterStatus(ms); setReport(r); setError(null); }
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
        <div className="bg-red-50 border border-red-100 rounded-2xl p-4 text-red-700 text-sm">{error}</div>
      </div>
    );
  }

  if (!status || !report) {
    return (
      <div className="p-6">
        <h1 className="text-2xl font-bold mb-4">Time Synchronization</h1>
        <p className="text-gray-400">Loading...</p>
      </div>
    );
  }

  const synced           = status.synced;
  const masterNodeId     = status.berkeleyMasterNodeId;
  const roundNum         = status.berkeleyRoundNumber;
  // masterStatus is fetched directly from the master node, so these always show
  // the master's own Last δ and accumulated correction — not the sticky connected node's.
  const src              = masterStatus ?? status;
  const lastRoundDelta   = src.lastRoundDeltaMs ?? 0;
  const totalCorrection  = src.berkeleyCorrectionMs ?? 0;
  const lastRound        = status.lastBerkeleyRoundMs;

  const maxDelta = report.skewDetails?.length > 0
    ? Math.max(...report.skewDetails.map(s => Math.abs(s.correctionDeltaMs ?? 0)))
    : 0;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Time Synchronization</h1>
        <p className="text-sm text-gray-400 mt-0.5">
          Berkeley Algorithm - master collects peer times, computes cluster average, pushes correction deltas
        </p>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
        <StatCard label="Sync Health">
          <span className={`inline-block mt-1 px-2.5 py-1 rounded-lg text-sm font-bold ${
            synced ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
          }`}>
            {synced ? 'IN SYNC' : 'SKEW ALERT'}
          </span>
        </StatCard>

        <StatCard label="Berkeley Master">
          <span className="text-2xl font-bold text-gray-800 mt-1 block">
            {masterNodeId > 0 ? `Node ${masterNodeId}` : '-'}
          </span>
        </StatCard>
        <StatCard label="Max Correction Delta">
          <span className="text-2xl font-bold text-gray-800 mt-1 block">
            {maxDelta} <span className="text-base font-normal text-gray-400">ms</span>
          </span>
        </StatCard>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
          <div className="flex items-center gap-2 mb-3">
            <div className="w-7 h-7 bg-blue-50 rounded-lg flex items-center justify-center">
              <FiStar size={13} className="text-blue-600" />
            </div>
            <span className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest">Berkeley Round</span>
          </div>
          <div className="space-y-1.5 text-sm">
            <div className="flex justify-between">
              <span className="text-gray-400">Round #</span>
              <span className="font-mono font-semibold text-gray-800">{roundNum}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-400">Last round</span>
              <span className="font-mono font-semibold text-gray-800">{msAgo(lastRound)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-400">Last δ applied</span>
              <span className={`font-mono font-semibold ${Math.abs(lastRoundDelta) > 0 ? 'text-blue-600' : 'text-gray-800'}`}>
                {lastRoundDelta > 0 ? '+' : ''}{lastRoundDelta} ms
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-400">Accumulated correction</span>
              <span className={`font-mono font-semibold ${Math.abs(totalCorrection) > 0 ? 'text-purple-600' : 'text-gray-800'}`}>
                {totalCorrection > 0 ? '+' : ''}{totalCorrection} ms
              </span>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
          <div className="flex items-center gap-2 mb-3">
            <div className="w-7 h-7 bg-purple-50 rounded-lg flex items-center justify-center">
              <FiClock size={13} className="text-purple-600" />
            </div>
            <span className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest">Hybrid Logical Clock</span>
          </div>
          <div className="space-y-1.5 text-sm">
            <div className="flex justify-between">
              <span className="text-gray-400">Physical</span>
              <span className="font-mono font-semibold text-gray-800">{status.hlcPhysicalTime}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-gray-400">Logical counter</span>
              <span className="font-mono font-semibold text-gray-800">{status.hlcLogicalCounter}</span>
            </div>
          </div>
        </div>

        <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
          <div className="flex items-center gap-2 mb-3">
            <div className="w-7 h-7 bg-orange-50 rounded-lg flex items-center justify-center">
              <FiActivity size={13} className="text-orange-500" />
            </div>
            <span className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest">Lamport Timestamp</span>
          </div>
          <span className="text-3xl font-bold text-gray-800 font-mono">{status.lamportTimestamp}</span>
        </div>
      </div>

      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-800">Per-Node Correction Deltas</h2>
          <span className="text-xs text-gray-400">di = avgTime - nodeTimei (from last master round)</span>
        </div>

        {report.skewDetails && report.skewDetails.length > 0 ? (
          <table className="min-w-full divide-y divide-gray-100 text-sm">
            <thead className="bg-gray-50">
              <tr>
                {['Node', 'Status', 'Node Time', 'Correction d', 'Peak |d|', 'Alert', 'Measured'].map(h => (
                  <th key={h} className="px-4 py-3 text-left font-medium text-gray-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50">
              {report.skewDetails.map((n) => {
                const alert      = n.alertTriggered;
                const nodeStatus = n.nodeStatus ?? 'HEALTHY';
                const isDown     = nodeStatus === 'FAILED' || nodeStatus === 'UNREACHABLE';
                const delta      = n.correctionDeltaMs ?? 0;
                return (
                  <tr key={n.nodeId} className={`hover:bg-gray-50 transition-colors ${isDown ? 'opacity-60' : ''}`}>
                    <td className="px-4 py-3 font-medium text-gray-800">Node {n.nodeId}</td>
                    <td className="px-4 py-3">
                      {nodeStatus === 'FAILED'
                        ? <span className="px-2 py-0.5 rounded-lg text-xs font-semibold bg-red-100 text-red-600">FAILED</span>
                        : nodeStatus === 'UNREACHABLE'
                          ? <span className="px-2 py-0.5 rounded-lg text-xs font-semibold bg-gray-100 text-gray-500">UNREACHABLE</span>
                          : <span className="px-2 py-0.5 rounded-lg text-xs font-semibold bg-green-100 text-green-700">HEALTHY</span>
                      }
                    </td>
                    <td className="px-4 py-3 font-mono text-gray-500">
                      {isDown || !n.nodeTimeMs ? '-' : (() => {
                        const d = new Date(n.nodeTimeMs);
                        const hh = String(d.getHours()).padStart(2, '0');
                        const mm = String(d.getMinutes()).padStart(2, '0');
                        const ss = String(d.getSeconds()).padStart(2, '0');
                        const ms = String(d.getMilliseconds()).padStart(3, '0');
                        return `${hh}:${mm}:${ss}:${ms}`;
                      })()}
                    </td>
                    <td className={`px-4 py-3 font-mono font-semibold ${!isDown && alert ? 'text-red-500' : 'text-blue-600'}`}>
                      {isDown ? '-' : `${delta > 0 ? '+' : ''}${delta} ms`}
                    </td>
                    <td className="px-4 py-3 font-mono text-gray-500">
                      {isDown ? '-' : `${n.maxSkewMillis} ms`}
                    </td>
                    <td className="px-4 py-3">
                      {isDown
                        ? <span className="inline-block w-2.5 h-2.5 rounded-full bg-gray-300" />
                        : alert
                          ? <span className="inline-block w-2.5 h-2.5 rounded-full bg-red-500" />
                          : <span className="inline-block w-2.5 h-2.5 rounded-full bg-green-500" />
                      }
                    </td>
                    <td className="px-4 py-3 text-gray-500">
                      {isDown && !n.lastMeasuredAtMs ? '-' : msAgo(n.lastMeasuredAtMs)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        ) : (
          <p className="px-4 py-10 text-gray-400 text-center">
            No corrections yet - waiting for first Berkeley round...
          </p>
        )}
      </div>

    </div>
  );
}

function StatCard({ label, children }) {
  return (
    <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
      <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest">{label}</p>
      {children}
    </div>
  );
}