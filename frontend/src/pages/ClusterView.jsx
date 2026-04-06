import { useState, useEffect, useCallback } from 'react';
import { FiServer, FiRefreshCw, FiZap, FiWifi, FiWifiOff } from 'react-icons/fi';
import { getFaultStatus, getConsensusStatus, simulateFailure, simulateRecovery } from '../services/api.js';

const POLL_MS = 1000;

export default function ClusterView() {
  const [fault, setFault] = useState(null);
  const [consensus, setConsensus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');
  const [msgType, setMsgType] = useState('info');

  const refresh = useCallback(async () => {
    try {
      const [f, c] = await Promise.allSettled([getFaultStatus(), getConsensusStatus()]);
      if (f.status === 'fulfilled') setFault(f.value);
      if (c.status === 'fulfilled') setConsensus(c.value);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, POLL_MS);
    return () => clearInterval(id);
  }, [refresh]);

  const handleFail = async (nodeId) => {
    try {
      await simulateFailure(nodeId);
      setMsg(`Node ${nodeId} failure simulated`);
      setMsgType('error');
      setTimeout(refresh, 800);
    } catch { setMsg('Simulate failure failed'); setMsgType('error'); }
    setTimeout(() => setMsg(''), 3000);
  };

  const handleRecover = async (nodeId) => {
    try {
      await simulateRecovery(nodeId);
      setMsg(`Node ${nodeId} recovery triggered`);
      setMsgType('success');
      setTimeout(refresh, 800);
    } catch { setMsg('Simulate recovery failed'); setMsgType('error'); }
    setTimeout(() => setMsg(''), 3000);
  };

  const nodes = fault ? Object.values(fault.nodeHealthMap ?? {}).sort((a, b) => a.nodeId - b.nodeId) : [];
  const leaderId = consensus?.leaderId;

  if (loading && !fault) {
    return (
      <div className="flex flex-col items-center gap-3 py-24 text-gray-400 text-sm">
        <div className="w-10 h-10 border-4 border-gray-100 border-t-[#0078d4] rounded-full animate-spin" />
        Loading cluster info…
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Page header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Cluster Status</h1>
          <p className="text-sm text-gray-400 mt-0.5">5-node ZAB cluster · auto-refreshes every 1s</p>
        </div>
        <button
          onClick={refresh}
          className="flex items-center gap-2 px-3.5 py-2 text-sm font-medium bg-white hover:bg-gray-50 border border-gray-200 rounded-xl transition shadow-sm"
        >
          <FiRefreshCw size={13} /> Refresh
        </button>
      </div>

      {msg && (
        <div className={`px-4 py-3 rounded-xl text-sm font-medium ${
          msgType === 'success' ? 'bg-green-50 text-green-700 border border-green-100'
          : msgType === 'error' ? 'bg-red-50 text-red-700 border border-red-100'
          : 'bg-blue-50 text-blue-700 border border-blue-100'
        }`}>{msg}</div>
      )}

      {/* Summary strip */}
      {fault && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <StatCard label="Cluster State">
            <StateChip state={fault.clusterState} />
          </StatCard>
          <StatCard label="Nodes">
            <span className="text-2xl font-bold text-gray-800">{fault.healthyNodes}</span>
            <span className="text-gray-400 font-normal text-lg">/{fault.totalNodes}</span>
            <span className="text-sm text-gray-500 ml-1">healthy</span>
          </StatCard>
          <StatCard label="Quorum">
            <span className={`text-lg font-bold ${fault.hasQuorum ? 'text-green-600' : 'text-red-600'}`}>
              {fault.hasQuorum ? 'Established' : 'Lost'}
            </span>
          </StatCard>
          <StatCard label="ZAB Leader">
            <span className="text-lg font-bold text-[#0078d4]">
              {leaderId ? `Node ${leaderId}` : '—'}
            </span>
          </StatCard>
        </div>
      )}

      {/* Node cards */}
      <div className="grid grid-cols-[repeat(auto-fill,minmax(210px,1fr))] gap-4">
        {nodes.map((node) => {
          const alive = node.alive;
          const isLeader = node.nodeId === leaderId;
          return (
            <div
              key={node.nodeId}
              className={`bg-white rounded-2xl border-2 p-5 transition-all
                ${
                  !alive ? 'border-red-100 opacity-70'
                  : isLeader ? 'border-[#0078d4] shadow-md shadow-blue-100'
                  : 'border-gray-100 hover:border-gray-200 hover:shadow-sm'
                }`}
            >
              {/* Card header */}
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-2.5">
                  <div className={`w-9 h-9 rounded-xl flex items-center justify-center
                    ${!alive ? 'bg-red-50' : isLeader ? 'bg-[#0078d4]' : 'bg-gray-100'}`}>
                    <FiServer size={16} className={!alive ? 'text-red-400' : isLeader ? 'text-white' : 'text-gray-500'} />
                  </div>
                  <div>
                    <div className="text-[14px] font-semibold text-gray-800">Node {node.nodeId}</div>
                    <div className="text-[11px] text-gray-400">:{8079 + node.nodeId}</div>
                  </div>
                </div>
                <RoleBadge alive={alive} isLeader={isLeader} />
              </div>

              {/* Status row */}
              <div className="space-y-2 mb-4">
                <InfoRow label="Status">
                  <span className={`flex items-center gap-1.5 font-medium ${
                    alive ? 'text-green-600' : 'text-red-500'
                  }`}>
                    <span className={`w-1.5 h-1.5 rounded-full ${
                      alive ? 'bg-green-500' : 'bg-red-500'
                    }`} />
                    {alive ? 'Healthy' : 'Unreachable'}
                  </span>
                </InfoRow>
                {node.lastHeartbeatMs > 0 && (
                  <InfoRow label="Last Beat">
                    {new Date(node.lastHeartbeatMs).toLocaleTimeString()}
                  </InfoRow>
                )}
                {!alive && node.failureReason && (
                  <InfoRow label="Reason">
                    <span className="text-red-400 text-[11px]">{node.failureReason}</span>
                  </InfoRow>
                )}
              </div>

              {/* Action button */}
              {alive ? (
                <button
                  onClick={() => handleFail(node.nodeId)}
                  className="w-full py-2 rounded-xl text-xs font-semibold bg-red-50 text-red-600 hover:bg-red-600 hover:text-white border border-red-100 hover:border-red-600 transition">
                  Simulate Failure
                </button>
              ) : (
                <button
                  onClick={() => handleRecover(node.nodeId)}
                  className="w-full py-2 rounded-xl text-xs font-semibold bg-[#0078d4]/10 text-[#0078d4] hover:bg-[#0078d4] hover:text-white border border-[#0078d4]/20 hover:border-[#0078d4] transition">
                  Simulate Recovery
                </button>
              )}
            </div>
          );
        })}
        {nodes.length === 0 && (
          <p className="col-span-full text-sm text-gray-400">No node data — is the backend running?</p>
        )}
      </div>

      {/* Consensus + Partition panels */}
      {consensus && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {/* ZAB */}
          <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
            <div className="flex items-center gap-2 mb-4">
              <div className="w-8 h-8 bg-[#0078d4]/10 rounded-lg flex items-center justify-center">
                <FiZap size={15} className="text-[#0078d4]" />
              </div>
              <h2 className="font-semibold text-gray-800">ZAB Consensus</h2>
            </div>
            <div className="space-y-3">
              <InfoRow label="Leader"><span className="font-semibold text-[#0078d4]">Node {consensus.leaderId ?? '—'}</span></InfoRow>
              <InfoRow label="Election Epoch">{consensus.electionEpoch ?? '—'}</InfoRow>
              <InfoRow label="ZXID"><span className="font-mono">{consensus.zxid ?? '—'}</span></InfoRow>
              <InfoRow label="Leader Alive">
                <span className={`flex items-center gap-1.5 font-medium ${consensus.leaderAlive ? 'text-green-600' : 'text-red-600'}`}>
                  <span className={`w-1.5 h-1.5 rounded-full ${consensus.leaderAlive ? 'bg-green-500' : 'bg-red-500'}`} />
                  {consensus.leaderAlive ? 'Yes' : 'No'}
                </span>
              </InfoRow>
              <InfoRow label="Quorum Write">
                <span className={`font-semibold ${consensus.canWrite ? 'text-green-600' : 'text-red-600'}`}>
                  {consensus.canWrite ? 'Available' : 'Blocked'}
                </span>
              </InfoRow>
            </div>
          </div>

          {/* Partition */}
          <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
            <div className="flex items-center gap-2 mb-4">
              <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${
                consensus.partitioned ? 'bg-red-50' : 'bg-green-50'
              }`}>
                {consensus.partitioned
                  ? <FiWifiOff size={15} className="text-red-500" />
                  : <FiWifi size={15} className="text-green-500" />}
              </div>
              <h2 className="font-semibold text-gray-800">Network Partition</h2>
            </div>
            <div className="space-y-3">
              <InfoRow label="Status">
                <span className={`font-semibold ${consensus.partitioned ? 'text-red-600' : 'text-green-600'}`}>
                  {consensus.partitioned ? 'Partition detected' : 'Fully connected'}
                </span>
              </InfoRow>
              <InfoRow label="Reachable Nodes">
                <span className="font-semibold">{consensus.reachableNodes ?? '—'} / 5</span>
              </InfoRow>
              <InfoRow label="Write Quorum">
                <span className={`font-semibold ${consensus.canWrite ? 'text-green-600' : 'text-red-600'}`}>
                  {consensus.canWrite ? '≥ 3 nodes · Available' : '< 3 nodes · Blocked'}
                </span>
              </InfoRow>
              <InfoRow label="Read Quorum">
                <span className={`font-semibold ${
                  (consensus.reachableNodes ?? 0) >= 3 ? 'text-green-600' : 'text-red-600'
                }`}>
                  {(consensus.reachableNodes ?? 0) >= 3 ? '≥ 3 nodes · Available' : '< 3 nodes · Blocked'}
                </span>
              </InfoRow>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, children }) {
  return (
    <div className="bg-white rounded-2xl border border-gray-100 p-5 shadow-sm">
      <p className="text-[11px] font-semibold text-gray-400 uppercase tracking-widest mb-2">{label}</p>
      <div className="flex items-baseline gap-1">{children}</div>
    </div>
  );
}

function StateChip({ state }) {
  const styles = {
    HEALTHY:  'bg-green-100 text-green-700',
    DEGRADED: 'bg-yellow-100 text-yellow-700',
    CRITICAL: 'bg-red-100 text-red-700',
  };
  return (
    <span className={`inline-block px-2.5 py-1 rounded-lg text-sm font-bold ${styles[state] ?? 'bg-gray-100 text-gray-600'}`}>
      {state}
    </span>
  );
}

function RoleBadge({ alive, isLeader }) {
  if (!alive) return <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-red-50 text-red-500">DOWN</span>;
  if (isLeader) return <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-[#0078d4]/10 text-[#0078d4]">LEADER</span>;
  return <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-gray-100 text-gray-500">FOLLOWER</span>;
}

function InfoRow({ label, children }) {
  return (
    <div className="flex items-center justify-between text-[13px]">
      <span className="text-gray-400">{label}</span>
      <span className="text-gray-700">{children}</span>
    </div>
  );
}
