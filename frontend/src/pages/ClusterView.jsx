/**
 * CloudBox — Cluster Status page.
 *
 * Admin view: per-node health cards, consensus info, time sync,
 * and simulate failure/recovery controls.
 */

import { useState, useEffect, useCallback } from 'react';
import {
  FiServer, FiRefreshCw,
  FiCheckCircle, FiClock, FiShield,
} from 'react-icons/fi';
import {
  getClusterStatus, getConsensusStatus, getTimeSyncStatus,
  simulateFailure, simulateRecovery,
} from '../services/api.js';

export default function ClusterView() {
  const [cluster, setCluster] = useState(null);
  const [consensus, setConsensus] = useState(null);
  const [timeSync, setTimeSync] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionMsg, setActionMsg] = useState('');

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [c, cn, ts] = await Promise.allSettled([
        getClusterStatus(),
        getConsensusStatus(),
        getTimeSyncStatus(),
      ]);
      if (c.status === 'fulfilled') setCluster(c.value);
      if (cn.status === 'fulfilled') setConsensus(cn.value);
      if (ts.status === 'fulfilled') setTimeSync(ts.value);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const id = setInterval(refresh, 10000);
    return () => clearInterval(id);
  }, [refresh]);

  const handleSimFailure = async (nodeId) => {
    try {
      const res = await simulateFailure(nodeId);
      setActionMsg(res?.message || 'Node failure simulated');
      refresh();
    } catch { setActionMsg('Simulate failure failed'); }
  };

  const handleSimRecovery = async (nodeId) => {
    try {
      const res = await simulateRecovery(nodeId);
      setActionMsg(res?.message || 'Node recovery simulated');
      refresh();
    } catch { setActionMsg('Simulate recovery failed'); }
  };

  const nodes = cluster?.nodes ?? [];

  if (loading && !cluster) {
    return (
      <div className="flex flex-col items-center gap-3 py-12 text-gray-400 text-sm">
        <div className="w-8 h-8 border-3 border-gray-200 border-t-[#0078d4] rounded-full animate-spin" />
        Loading cluster info…
      </div>
    );
  }

  return (
    <>
      {/* Title bar */}
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-xl font-semibold">Cluster Status</h2>
        <button onClick={refresh} className="flex items-center gap-1.5 px-3 py-1.5 text-[13px] font-semibold bg-gray-100 hover:bg-gray-200 rounded border border-gray-200 transition">
          <FiRefreshCw /> Refresh
        </button>
      </div>

      {actionMsg && (
        <div className="mb-4 bg-green-50 text-green-700 px-3 py-2 rounded text-[13px]">{actionMsg}</div>
      )}

      {/* Node cards */}
      <div className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-4 mb-6">
        {nodes.map((node) => {
          const alive = node.status === 'alive' || node.alive;
          const isLeader = node.role === 'leader' || node.isLeader;
          const nodeId = node.id ?? node.nodeId;
          return (
            <div
              key={nodeId}
              className={`bg-white border rounded-lg p-4 transition hover:shadow-sm
                ${isLeader ? 'border-[#0078d4] border-2' : 'border-gray-200'}
                ${!alive ? 'opacity-50 bg-gray-50' : ''}`}
            >
              <div className="flex items-center justify-between mb-3">
                <h3 className="flex items-center gap-1.5 text-[15px] font-semibold">
                  <FiServer /> Node {nodeId}
                </h3>
                <span className={`px-2 py-0.5 rounded-full text-[11px] font-bold uppercase
                  ${!alive ? 'bg-red-50 text-red-600' : isLeader ? 'bg-blue-50 text-[#0078d4]' : 'bg-gray-100 text-gray-500'}`}>
                  {!alive ? 'Down' : isLeader ? 'Leader' : 'Follower'}
                </span>
              </div>

              <div className="flex justify-between py-1 text-[13px]">
                <span className="text-gray-500">Status</span>
                <span className="flex items-center gap-1.5">
                  <span className={`w-2 h-2 rounded-full inline-block ${alive ? 'bg-green-500' : 'bg-red-500'}`} />
                  {alive ? 'Healthy' : 'Unreachable'}
                </span>
              </div>

              {node.port && (
                <div className="flex justify-between py-1 text-[13px]">
                  <span className="text-gray-500">Port</span>
                  <span>{node.port}</span>
                </div>
              )}

              {node.lastHeartbeat && (
                <div className="flex justify-between py-1 text-[13px]">
                  <span className="text-gray-500">Last heartbeat</span>
                  <span>{new Date(node.lastHeartbeat).toLocaleTimeString()}</span>
                </div>
              )}

              {/* Simulate buttons */}
              <div className="flex gap-2 mt-3">
                {alive ? (
                  <button onClick={() => handleSimFailure(nodeId)}
                    className="flex-1 text-[12px] font-semibold px-2 py-1 bg-red-600 hover:bg-red-700 text-white rounded transition">
                    Simulate Failure
                  </button>
                ) : (
                  <button onClick={() => handleSimRecovery(nodeId)}
                    className="flex-1 text-[12px] font-semibold px-2 py-1 bg-[#0078d4] hover:bg-[#106ebe] text-white rounded transition">
                    Simulate Recovery
                  </button>
                )}
              </div>
            </div>
          );
        })}
        {nodes.length === 0 && <p className="text-sm text-gray-400">No node data available yet.</p>}
      </div>

      {/* Consensus info */}
      {consensus && (
        <section className="mb-6">
          <h3 className="flex items-center gap-1.5 text-base font-semibold mb-2">
            <FiCheckCircle className="text-[#0078d4]" /> Consensus (ZAB Protocol)
          </h3>
          <div className="bg-white border border-gray-200 rounded-lg p-4 text-[13px] space-y-1">
            <div className="flex justify-between"><span className="text-gray-500">Leader</span><span>Node {consensus.leader ?? '—'}</span></div>
            <div className="flex justify-between"><span className="text-gray-500">Epoch</span><span>{consensus.epoch ?? '—'}</span></div>
            <div className="flex justify-between"><span className="text-gray-500">ZXID</span><span>{consensus.zxid ?? '—'}</span></div>
            <div className="flex justify-between">
              <span className="text-gray-500">Leader status</span>
              <span className="flex items-center gap-1.5">
                <span className={`w-2 h-2 rounded-full inline-block ${consensus.alive ? 'bg-green-500' : 'bg-red-500'}`} />
                {consensus.alive ? 'Alive' : 'Unreachable'}
              </span>
            </div>
            {consensus.lastHeartbeat > 0 && (
              <div className="flex justify-between"><span className="text-gray-500">Leader heartbeat</span><span>{new Date(consensus.lastHeartbeat).toLocaleTimeString()}</span></div>
            )}
          </div>
        </section>
      )}

      {/* Partition status */}
      {cluster?.partitionStatus && (
        <section className="mb-6">
          <h3 className="flex items-center gap-1.5 text-base font-semibold mb-2">
            <FiShield className={cluster.partitionStatus.partitioned ? 'text-red-500' : 'text-green-600'} /> Partition Status
          </h3>
          <div className="bg-white border border-gray-200 rounded-lg p-4 text-[13px] space-y-1">
            <div className="flex justify-between">
              <span className="text-gray-500">Network</span>
              <span className={`font-semibold ${cluster.partitionStatus.partitioned ? 'text-red-600' : 'text-green-600'}`}>
                {cluster.partitionStatus.partitioned ? 'Partitioned' : 'Connected'}
              </span>
            </div>
            <div className="flex justify-between"><span className="text-gray-500">Reachable nodes</span><span>{cluster.partitionStatus.reachableNodes ?? '—'} / 5</span></div>
            <div className="flex justify-between">
              <span className="text-gray-500">Quorum write</span>
              <span className={`font-semibold ${cluster.partitionStatus.canWrite ? 'text-green-600' : 'text-red-600'}`}>
                {cluster.partitionStatus.canWrite ? 'Available' : 'Blocked'}
              </span>
            </div>
            {cluster.partitionStatus.partitionDescription && (
              <div className="flex justify-between"><span className="text-gray-500">Description</span><span>{cluster.partitionStatus.partitionDescription}</span></div>
            )}
          </div>
        </section>
      )}

      {/* Time sync info */}
      {timeSync && (
        <section className="mb-6">
          <h3 className="flex items-center gap-1.5 text-base font-semibold mb-2">
            <FiClock className="text-[#0078d4]" /> Time Synchronization
          </h3>
          <div className="bg-white border border-gray-200 rounded-lg p-4 text-[13px] space-y-1">
            <div className="flex justify-between"><span className="text-gray-500">Offset</span><span>{timeSync.offset != null ? `${timeSync.offset.toFixed(3)} s` : '—'}</span></div>
            <div className="flex justify-between"><span className="text-gray-500">Last sync</span><span>{timeSync.lastSync ? new Date(timeSync.lastSync).toLocaleTimeString() : '—'}</span></div>
          </div>
        </section>
      )}
    </>
  );
}
