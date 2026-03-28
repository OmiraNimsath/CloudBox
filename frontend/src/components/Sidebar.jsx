/**
 * CloudBox — Sidebar component (OneDrive-style left nav).
 */

import { NavLink } from 'react-router-dom';
import { FiFolder, FiServer, FiAlertTriangle } from 'react-icons/fi';

const links = [
  { to: '/', label: 'My Files', icon: <FiFolder /> },
  { to: '/cluster', label: 'Cluster Status', icon: <FiServer /> },
  { to: '/fault-tolerance', label: 'Fault Tolerance', icon: <FiAlertTriangle /> },
];

export default function Sidebar() {
  return (
    <aside className="w-56 bg-white border-r border-gray-200 py-4 flex flex-col shrink-0">
      <ul className="space-y-0.5">
        {links.map((lnk) => (
          <li key={lnk.to}>
            <NavLink
              to={lnk.to}
              end={lnk.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 px-5 py-2.5 text-sm transition-colors
                 ${isActive
                    ? 'bg-gray-200 font-semibold text-[#0078d4]'
                    : 'text-gray-700 hover:bg-gray-100'
                 }`
              }
            >
              <span className="text-lg">{lnk.icon}</span>
              {lnk.label}
            </NavLink>
          </li>
        ))}
      </ul>
    </aside>
  );
}
