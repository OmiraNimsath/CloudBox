/**
 * CloudBox — Root application component.
 *
 * Routes between Dashboard (file manager) and Cluster Status views.
 */

import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useState, useEffect, useCallback } from 'react';

import Header from './components/Header.jsx';
import Sidebar from './components/Sidebar.jsx';
import Dashboard from './pages/Dashboard.jsx';
import ClusterView from './pages/ClusterView.jsx';
import FaultTolerancePage from './pages/FaultTolerancePage.jsx';
import ReplicationPage from './pages/ReplicationPage.jsx';
import TimeSyncPage from './pages/TimeSyncPage.jsx';
import { getClusterStatus } from './services/api.js';

export default function App() {
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [clusterInfo, setClusterInfo] = useState(null);

  const fetchCluster = useCallback(async () => {
    try {
      const data = await getClusterStatus();
      setClusterInfo(data);
    } catch {
      /* backend not running yet */
    }
  }, []);

  useEffect(() => {
    fetchCluster();
    const id = setInterval(fetchCluster, 10000);
    return () => clearInterval(id);
  }, [fetchCluster]);

  return (
    <BrowserRouter>
      <div className="flex flex-col h-screen">
        <Header
          clusterInfo={clusterInfo}
          onUploadClick={() => setUploadModalOpen(true)}
        />
        <div className="flex flex-1 overflow-hidden">
          <Sidebar />
          <main className="flex-1 overflow-y-auto p-6 bg-gray-50">
            <Routes>
              <Route
                path="/"
                element={
                  <Dashboard
                    uploadModalOpen={uploadModalOpen}
                    setUploadModalOpen={setUploadModalOpen}
                  />
                }
              />
              <Route path="/cluster" element={<ClusterView />} />
              <Route path="/fault-tolerance" element={<FaultTolerancePage />} />
              <Route path="/replication" element={<ReplicationPage />} />
              <Route path="/time-sync" element={<TimeSyncPage />} />
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </main>
        </div>
      </div>
    </BrowserRouter>
  );
}
