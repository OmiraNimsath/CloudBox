import { BrowserRouter, Routes, Route, Navigate, useNavigate } from 'react-router-dom';
import { useState, useEffect, useCallback } from 'react';

import Header from './components/Header.jsx';
import Sidebar from './components/Sidebar.jsx';
import Dashboard from './pages/Dashboard.jsx';
import ClusterView from './pages/ClusterView.jsx';
import FaultTolerancePage from './pages/FaultTolerancePage.jsx';
import ReplicationPage from './pages/ReplicationPage.jsx';
import TimeSyncPage from './pages/TimeSyncPage.jsx';
import { getFaultStatus } from './services/api.js';

function AppContent() {
  const navigate = useNavigate();
  const [uploadModalOpen, setUploadModalOpen] = useState(false);
  const [clusterInfo, setClusterInfo] = useState(null);

  const fetchCluster = useCallback(async () => {
    try {
      const data = await getFaultStatus();
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

  const handleUploadClick = () => {
    navigate('/');
    setUploadModalOpen(true);
  };

  return (
    <div className="flex h-screen bg-gray-50">
      <Sidebar />
      <div className="flex flex-col flex-1 overflow-hidden">
        <Header
          clusterInfo={clusterInfo}
          onUploadClick={handleUploadClick}
        />
        <main className="flex-1 overflow-y-auto p-6">
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
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AppContent />
    </BrowserRouter>
  );
}
