/**
 * CloudBox — Centralized API service.
 *
 * All backend communication goes through this module.
 * The Vite dev server proxies /api to the Spring Boot backend.
 */

import axios from 'axios';

const api = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

// ── File operations ──────────────────────────────────────────────

/**
 * Upload a file to a given folder path.
 * @param {File} file
 * @param {string} folderPath – e.g. "/" or "/docs/"
 */
export async function uploadFile(file, folderPath = '/') {
  const form = new FormData();
  form.append('file', file);
  form.append('path', folderPath);
  const res = await api.post('/files/upload', form);
  return res.data;
}

/**
 * Download a file as a Blob.
 * @param {string} filePath – full logical path, e.g. "/docs/report.pdf"
 */
export async function downloadFile(filePath) {
  const res = await api.get('/files/download', {
    params: { path: filePath },
    responseType: 'blob',
  });
  return res.data;
}

/**
 * List contents of a folder.
 * @param {string} prefix – folder path, e.g. "/"
 */
export async function listFiles(prefix = '/') {
  const res = await api.get('/files/list', { params: { path: prefix } });
  return res.data.data;
}

/**
 * Delete a file or folder.
 * @param {string} filePath
 */
export async function deleteFile(filePath) {
  const res = await api.delete('/files/delete', { params: { path: filePath } });
  return res.data;
}

// ── Cluster status ───────────────────────────────────────────────

/** Get cluster-wide health overview. */
export async function getClusterStatus() {
  const res = await api.get('/cluster/consensus/status');
  const data = res.data.data;
  
  // Transform data format for frontend expectations { nodes: [{id, status, role}] }
  const nodes = [];
  let alive = 0;
  if (data && data.nodeStatuses) {
    for (const [id, status] of Object.entries(data.nodeStatuses)) {
      const isAlive = status === 'HEALTHY' || status === 'ACTIVE';
      if (isAlive) alive++;
      nodes.push({
        id: parseInt(id),
        status: isAlive ? 'alive' : 'dead',
        isLeader: data.leader?.leaderId === parseInt(id)
      });
    }
  }
  return { ...data, nodes, alive, total: Object.keys(data?.nodeStatuses || {}).length };
}

/** Get consensus / leader info. */
export async function getConsensusStatus() {
  const res = await api.get('/cluster/consensus/leader');
  const data = res.data.data;
  return { ...data, leader: data?.leaderId, epoch: data?.electionEpoch };
}

/** Get time synchronization info. */
export async function getTimeSyncStatus() {
  const res = await api.get('/timesync/status');
  const data = res.data.data;
  return { ...data, offset: (data?.maxClockSkew || 0) / 1000, lastSync: data?.lastSyncAt };
}

// ── Admin / simulation ───────────────────────────────────────────

/** Simulate a node failure. */
export async function simulateFailure(nodeId) {
  const res = await api.post('/admin/simulate-failure', null, { params: { nodeId } });
  return res.data;
}

/** Simulate a node recovery. */
export async function simulateRecovery(nodeId) {
  const res = await api.post('/admin/simulate-recovery', null, { params: { nodeId } });
  return res.data;
}

/** Get node health. */
export async function getNodeHealth() {
  const res = await api.get('/health');
  return res.data;
}
