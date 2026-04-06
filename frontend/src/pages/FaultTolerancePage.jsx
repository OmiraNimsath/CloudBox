import { useState, useEffect } from 'react';
import { FiAlertTriangle, FiActivity, FiShield } from 'react-icons/fi';
import { getFaultStatus, simulateFailure, simulateRecovery } from '../services/api.js';

const POLL_MS = 1000;

function fmtHeartbeat(ms) {
  if (!ms) return 'never';
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function fmtSecs(s) {
  if (s == null || s < 0) return '—';
  if (s < 60)   return `${Math.round(s)}s`;
  if (s < 3600) return `${Math.round(s / 60)}m`;
  return `${(s / 3600).toFixed(1)}h`;
}

export default function FaultTolerancePage() {
  const [status, setStatus] = useState(null);
  const [error, setError]   = useState(null);
  const [msg, setMsg]       = useState('');
  const [msgType, setMsgType] = useState('info');

  useEffect(() => {
    const load = async () => {
      try { setStatus(await getFaultStatus()); setError(null); }
      catch { setError('Backend unreachable — retrying…'); }
    };
    load();
    const id = setInterval(load, POLL_MS);
    return () => clearInterval(id);
  }, []);

  const doFail = async (nodeId) => {
    try { await simulateFailure(nodeId); setMsg(`Node ${nodeId} failure triggered`); setMsgType('error'); }
    catch { setMsg('Simulate failure failed'); setMsgType('error'); }
    setTimeout(() => setMsg(''), 3000);
  };

  const doRecover = async (nodeId) => {
    try { await simulateRecovery(nodeId); setMsg(`Node ${nodeId} recovery triggered`); setMsgType('success'); }
    catch { setMsg('Simulate recovery failed'); setMsgType('error'); }
    setTimeout(() => setMsg(''), 3000);
  };

  if (error && !status) return <div className="p-6 text-red-500">{error}</div>;
  if (!status)          return <div className="p-6 text-gray-400">Loading fault tolerance status…</div>;

  const nodes = Object.values(status.nodeHealthMap ?? {}).sort((a, b) => a.nodeId - b.nodeId);
  const stateStyle = {
    HEALTHY:  'bg-green-100 text-green-700',
    DEGRADED: 'bg-yellow-100 text-yellow-700',
    CRITICAL: 'bg-red-100 text-red-700',
  };

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div>
        <h1 className="text-2xl font-bold text-gray-900">Fault Tolerance</h1>
        <p className="text-sm text-gray-400 mt-0.5">Heartbeat monitoring, failure detection, and reliability metrics</p>
      </div>

      {msg && (
        <div className={`px-4 py-3 rounded-xl text-sm font-medium border ${
          msgType === 'success' ? 'bg-green-50 text-green-700 border-green-100'
          : msgType === 'error'   ? 'bg-red-50 text-red-700 border-red-100'
          : 'bg-blue-50 text-blue-700 border-blue-100'
        }`}>{msg}</div>
      )}

      {/* Summary cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard label="Cluster State">
          <span className={`inline-block px-2.5 py-1 rounded-lg text-sm font-bold mt-1 ${stateStyle[status.clusterState] ?? 'bg-gray-100 text-gray-600'}`}>
            {status.clusterState}
          </span>
        </StatCard>
        <StatCard label="Quorum">
          <p className={`text-xl font-bold mt-1 ${status.hasQuorum ? 'text-green-600' : 'text-red-600'}`}>
            {status.hasQuorum ? 'Established' : 'Lost'}
          </p>
        </StatCard>
        <StatCard label="Nodes">
          <p className="text-xl font-bold mt-1 text-gray-800">{status.healthyNodes}/{status.totalNodes} healthy</p>
        </StatCard>
        <StatCard label="Under-replicated Files">
          <p className={`text-xl font-bold mt-1 ${status.underReplicatedFiles > 0 ? 'text-red-600' : 'text-green-600'}`}>
            {status.underReplicatedFiles ?? 0}
          </p>
        </StatCard>
      </div>

      {/* Reliability metrics */}
      <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
        <div className="flex items-center gap-2 mb-5">
          <div className="w-8 h-8 bg-blue-50 rounded-lg flex items-center justify-center">
            <FiActivity size={15} className="text-blue-600" />
          </div>
          <h2 className="text-base font-semibold text-gray-800">Reliability Metrics</h2>
        </div>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-6">
          <Metric label="Availability" color="text-[#0078d4]"
            value={status.availabilityPercentage >= 0 ? `${status.availabilityPercentage.toFixed(2)}%` : '—'}
            sub="MTTF / (MTTF + MTTR)" />
          <Metric label="MTTF" value={fmtSecs(status.mttfSeconds)} sub="Mean Time To Failure" />
          <Metric label="MTTR" value={fmtSecs(status.mttrSeconds)} sub="Mean Time To Repair" />
        </div>

      </div>

      {/* Node health table */}
      <div className="bg-white rounded-2xl border border-gray-100 shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 bg-gray-50 rounded-lg flex items-center justify-center">
              <FiShield size={15} className="text-gray-500" />
            </div>
            <h2 className="text-base font-semibold text-gray-800">Node Health &amp; Heartbeat Monitor</h2>
          </div>
        </div>
        <table className="min-w-full divide-y divide-gray-100 text-sm">
          <thead className="bg-gray-50">
            <tr>
              {['Node', 'Status', 'Last Heartbeat', 'Missed Beats', 'Failure Reason', 'Action'].map(h => (
                <th key={h} className="px-4 py-3 text-left font-medium text-gray-500">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-50">
            {nodes.map(n => (
              <tr key={n.nodeId} className={`hover:bg-gray-50 transition-colors ${!n.alive ? 'opacity-60' : ''}`}>
                <td className="px-4 py-3 font-medium text-gray-800">Node {n.nodeId}</td>
                <td className={`px-4 py-3 font-semibold ${n.alive ? 'text-green-600' : 'text-red-500'}`}>
                  <span className="flex items-center gap-1.5">
                    <span className={`w-1.5 h-1.5 rounded-full ${n.alive ? 'bg-green-500' : 'bg-red-500'}`} />
                    {n.status}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-500">{fmtHeartbeat(n.lastHeartbeatMs)}</td>
                <td className="px-4 py-3">
                  <span className={`font-mono font-semibold ${
                    n.missedHeartbeats >= 3 ? 'text-red-500' : 'text-gray-600'
                  }`}>{n.missedHeartbeats}</span>
                </td>
                <td className="px-4 py-3 text-gray-400 italic text-[12px]">{n.failureReason ?? '—'}</td>
                <td className="px-4 py-3">
                  {n.alive
                    ? <button onClick={() => doFail(n.nodeId)}
                        className="text-xs font-semibold px-3 py-1.5 rounded-lg bg-red-50 text-red-600 hover:bg-red-600 hover:text-white border border-red-100 hover:border-red-600 transition">
                        Fail
                      </button>
                    : <button onClick={() => doRecover(n.nodeId)}
                        className="text-xs font-semibold px-3 py-1.5 rounded-lg bg-[#0078d4]/10 text-[#0078d4] hover:bg-[#0078d4] hover:text-white border border-[#0078d4]/20 hover:border-[#0078d4] transition">
                        Recover
                      </button>
                  }
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Recent failures */}
      {status.recentFailures?.length > 0 && (
        <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
          <div className="flex items-center gap-2 mb-4">
            <div className="w-8 h-8 bg-red-50 rounded-lg flex items-center justify-center">
              <FiAlertTriangle size={14} className="text-red-500" />
            </div>
            <h2 className="text-base font-semibold text-gray-800">Recent Failures</h2>
          </div>
          <ul className="space-y-1.5 text-sm">
            {status.recentFailures.map((f, i) => (
              <li key={i} className="flex items-start gap-2 text-gray-600">
                <span className="text-red-400 mt-0.5">✕</span>{f}
              </li>
            ))}
          </ul>
        </div>
      )}


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

function Metric({ label, value, sub, color = 'text-gray-800' }) {
  return (
    <div>
      <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${color}`}>{value}</p>
      <p className="text-xs text-gray-400 mt-0.5">{sub}</p>
    </div>
  );
}
