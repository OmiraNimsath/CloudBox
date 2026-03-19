# CloudBox — Fault-Tolerant Distributed File Storage System

A distributed file storage system built for the **Distributed Systems** module. The system ensures high availability, fault tolerance, and consistency while handling concurrent read/write operations from multiple clients across multiple servers.

---

## Architecture Overview

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 4.0.3, Maven |
| **Service Discovery & Coordination** | Apache ZooKeeper (via Spring Cloud Zookeeper) |
| **Frontend** | React 19, Vite 6, Tailwind CSS 4 |

### System Components

| Component | Responsibility | Member |
|-----------|---------------|--------|
| Fault Tolerance | Redundancy, failure detection, recovery | Member 1 |
| Data Replication & Consistency | Replication strategy, consistency model, conflict resolution | Member 2 |
| Time Synchronization | Clock sync (NTP/PTP), clock skew handling | Member 3 |
| Consensus & Agreement | Consensus algorithm, leader election, partition handling | Member 4 |

---

## Team Members

| Role | Registration No. | Email |
|------|-------------------|-------|
| Member 1 — Fault Tolerance | IT24103437 | it24103437@my.sliit.lk |
| Member 2 — Replication & Consistency | IT24103439 | it24103439@my.sliit.lk |
| Member 3 — Time Synchronization | IT24101495| it24101495@my.sliit.lk |
| Member 4 — Consensus & Agreement | IT24101842| it24101842@my.sliit.lk |

---

## Prerequisites

- **Java 21** (JDK)
- **Maven 3.9+** (or use the included `mvnw` wrapper)
- **Node.js 18+** and **npm 9+**
- **Apache ZooKeeper 3.8+** running on `localhost:2181`

---

## Quick Start

### 1. Start ZooKeeper

Download and run Apache ZooKeeper locally on the default port `2181`.

### 2. Backend

```bash
cd backend
./mvnw spring-boot:run
```

The API server starts on `http://localhost:8080` by default.

### 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

The dev server starts on `http://localhost:5173` with hot reload.

---

## Project Structure

```
CloudBox/
├── backend/                # Spring Boot application
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/cloudbox/   # Java source
│   │   │   └── resources/           # application.properties
│   │   └── test/                    # Unit & integration tests
│   ├── pom.xml                      # Maven config
│   └── mvnw / mvnw.cmd             # Maven wrapper
├── frontend/               # React + Vite application
│   ├── src/                         # React source
│   ├── public/                      # Static assets
│   ├── vite.config.js               # Vite + Tailwind config
│   └── package.json                 # npm config
├── .gitignore
└── README.md
```

---

## Key Dependencies

### Backend
- `spring-boot-starter-webmvc` — REST API
- `spring-cloud-starter-zookeeper-config` — Distributed configuration
- `spring-cloud-starter-zookeeper-discovery` — Service discovery & leader election
- `lombok` — Boilerplate reduction
- `spring-boot-devtools` — Hot reload during development

### Frontend
- `react` / `react-dom` — UI library
- `@vitejs/plugin-react` — Vite React integration
- `tailwindcss` / `@tailwindcss/vite` — Utility-first CSS
- `eslint` — Code linting

---

## Technical Specifications

| Decision | Choice |
|----------|--------|
| **Nodes** | 5 (tolerates 2 failures, quorum = 3) |
| **Consistency** | Quorum writes + leader reads |
| **Replication** | RF = 5 (all nodes) |
| **Folders** | Yes (path-based) |
| **File size** | 100 MB max |

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| Spring Cloud ZooKeeper | Manages leader election, cluster state, distributed config, and locks out of the box |
| Tailwind CSS | Rapid UI development with utility classes; OneDrive-inspired interface |
| Maven | Standard Java build tool; integrates with Spring ecosystem |
| Vite | Fast dev server with HMR for React |

---