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
  return res.data;
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
  const res = await api.get('/cluster/status');
  return res.data;
}

/** Get consensus / leader info. */
export async function getConsensusStatus() {
  const res = await api.get('/cluster/consensus');
  return res.data;
}

/** Get time synchronization info. */
export async function getTimeSyncStatus() {
  const res = await api.get('/cluster/time-sync');
  return res.data;
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
