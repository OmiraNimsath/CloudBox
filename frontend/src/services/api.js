import axios from 'axios';

const PORTS = [8080, 8081, 8082, 8083, 8084];
let portIdx = 0;

const getBase = () => `http://localhost:${PORTS[portIdx]}/api`;

// Short timeout so failover is fast (~800 ms instead of 5 s)
const api = axios.create({ timeout: 800 });

api.interceptors.request.use(cfg => {
  if (!cfg.url.startsWith('http')) cfg.baseURL = getBase();
  return cfg;
});

api.interceptors.response.use(
  res => res,
  async err => {
    const req = err.config;
    const networkDown = !err.response || err.response.status === 503 || err.message === 'Network Error';
    if (!networkDown || (req._retry ?? 0) >= PORTS.length) return Promise.reject(err);
    req._retry = (req._retry ?? 0) + 1;
    // Advance to next port and keep it sticky — don't cycle back to dead nodes
    portIdx = (portIdx + 1) % PORTS.length;
    req.baseURL = getBase();
    // Give the retry slightly more room than the initial fast probe
    req.timeout = 3000;
    return api(req);
  },
);

// ── Files ─────────────────────────────────────────────────────────────────

export async function uploadFile(file, path = '/') {
  const form = new FormData();
  form.append('file', file);
  form.append('path', path);
  const res = await api.post('/files/upload', form);
  return res.data;
}

export async function listFiles(path = '/') {
  const res = await api.get('/files/list', { params: { path } });
  return res.data.data;
}

export async function downloadFile(filePath) {
  const res = await api.get('/files/download', { params: { path: filePath }, responseType: 'blob' });
  return res.data;
}

export async function deleteFile(filePath) {
  const res = await api.delete('/files/delete', { params: { path: filePath } });
  return res.data;
}

// ── Fault tolerance ───────────────────────────────────────────────────────

export async function getFaultStatus() {
  const res = await api.get('/fault/status');
  return res.data.data;
}

// ── Replication ───────────────────────────────────────────────────────────

export async function getReplicationStatus() {
  const res = await api.get('/files/replication-status');
  return res.data.data;
}

// ── Consensus ─────────────────────────────────────────────────────────────

export async function getConsensusStatus() {
  const res = await api.get('/consensus/status');
  return res.data.data;
}

// ── Time synchronization ──────────────────────────────────────────────────

export async function getTimeSyncStatus() {
  const res = await api.get('/timesync/status');
  return res.data.data;
}

export async function getTimeSyncStatusFromNode(nodeId) {
  const res = await api.get(`http://localhost:${8079 + nodeId}/api/timesync/status`);
  return res.data.data;
}

export async function getSkewReport() {
  const res = await api.get('/timesync/skew-report');
  return res.data.data;
}

// ── Admin simulation ──────────────────────────────────────────────────────

export async function simulateFailure(nodeId) {
  const res = await api.post('/admin/simulate-failure', null, { params: { nodeId } });
  return res.data;
}

export async function simulateRecovery(nodeId) {
  const res = await api.post('/admin/simulate-recovery', null, { params: { nodeId } });
  return res.data;
}