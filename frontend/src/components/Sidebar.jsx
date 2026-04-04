import { NavLink } from 'react-router-dom';
import { FiFolder, FiServer, FiAlertTriangle, FiDatabase, FiClock, FiCloud } from 'react-icons/fi';

const links = [
  { to: '/', label: 'My Files', icon: FiFolder },
  { to: '/cluster', label: 'Cluster Status', icon: FiServer },
  { to: '/fault-tolerance', label: 'Fault Tolerance', icon: FiAlertTriangle },
  { to: '/replication', label: 'Replication', icon: FiDatabase },
  { to: '/time-sync', label: 'Time Sync', icon: FiClock },
];

export default function Sidebar() {
  return (
    <aside className="w-60 bg-white border-r border-gray-100 flex flex-col shrink-0">
      {/* Brand */}
      <div className="flex items-center gap-3 px-5 py-5 border-b border-gray-100">
        <div className="w-8 h-8 bg-[#0078d4] rounded-lg flex items-center justify-center shrink-0">
          <FiCloud className="text-white text-base" />
        </div>
        <div>
          <div className="text-gray-900 font-bold text-[16px] leading-tight">CloudBox</div>
          <div className="text-gray-400 text-[12px]">Distributed Storage</div>
        </div>
      </div>

      {/* Navigation */}
      <nav className="flex-1 py-4">
        <p className="px-5 mb-1 text-[11px] font-semibold text-gray-400 uppercase tracking-widest">Navigation</p>
        <ul className="space-y-0.5">
          {links.map(({ to, label, icon: Icon }) => (
            <li key={to}>
              <NavLink
                to={to}
                end={to === '/'}
                className={({ isActive }) =>
                  `flex items-center gap-3 mx-2 px-3 py-2.5 rounded-lg text-[14px] font-medium transition-all
                   ${isActive
                      ? 'bg-[#0078d4] text-white shadow-sm'
                      : 'text-gray-500 hover:bg-gray-50 hover:text-gray-800'
                   }`
                }
              >
                {({ isActive }) => (
                  <>
                    <Icon className={`text-[15px] shrink-0 ${isActive ? 'text-white' : 'text-gray-400'}`} />
                    {label}
                  </>
                )}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>

      {/* Footer */}
      <div className="px-5 py-4 border-t border-gray-100">
        <div className="text-[12px] text-gray-400">5-node cluster · RF=5 · Quorum=3</div>
      </div>
    </aside>
  );
}
