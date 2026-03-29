# CloudBox — Fault-Tolerant Distributed File Storage System

A distributed file storage system built for the **Distributed Systems** module. The system ensures high availability, fault tolerance, and consistency while handling concurrent read/write operations from multiple clients across multiple servers.

---

## Architecture Overview

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 4.0.3, Maven |
| **Service Discovery & Coordination** | Apache ZooKeeper (via Spring Cloud Zookeeper) |
| **Frontend** | React 19, Vite 6, Tailwind CSS 4 |
| **Architecture Pattern** | Port / Adapter (Hexagonal) |

### System Components

| Component | Responsibility | Member |
|-----------|---------------|--------|
| Fault Tolerance | Failure detection, heartbeat monitoring, async recovery, MTTF/MTTR metrics | IT24103437 |
| Data Replication & Storage | Quorum writes, replication factor management, path-traversal protection | IT24103439 |
| Time Synchronization | Cristian's Algorithm, clock-skew detection, HLC & Lamport clocks | IT24101495 |
| Consensus & Agreement | ZAB protocol, leader election, quorum ACK, partition handling | IT24101842 |

---

## Team Members

| Role | Registration No. | Email |
|------|-------------------|-------|
| Member 1 — Fault Tolerance | IT24103437 | it24103437@my.sliit.lk |
| Member 2 — Replication & Consistency | IT24103439 | it24103439@my.sliit.lk |
| Member 3 — Time Synchronization | IT24101495 | it24101495@my.sliit.lk |
| Member 4 — Consensus & Agreement | IT24101842 | it24101842@my.sliit.lk |

---

## Implemented Features

### Fault Tolerance (IT24103437)
- **Heartbeat-based failure detection** — periodic liveness checks across all nodes
- **Async recovery executor** — automatically re-replicates under-replicated files on node recovery
- **MTTF / MTTR / Availability metrics** — real-time reliability calculations exposed via REST API
- **Simulate failure / recovery** — admin endpoints to inject and resolve node failures for testing

### Data Replication & Consistency (IT24103439)
- **Quorum writes** — writes succeed only when a majority (3 of 5) of replicas acknowledge
- **Replication factor 5** — every file is replicated to all nodes for maximum durability
- **Path-traversal protection** — `sanitizeFileId()` prevents directory escape attacks
- **Replication status API** — per-file replica count and health visible from the dashboard

### Time Synchronization (IT24101495)
- **Cristian's Algorithm** — real NTP-style synchronization with RTT compensation
- **Clock-skew detection** — per-node skew monitoring with configurable alert thresholds
- **Hybrid Logical Clock (HLC)** — combines physical time with logical counters for causal ordering
- **Lamport timestamps** — event ordering for distributed operations

### Consensus & Agreement (IT24101842)
- **ZAB (ZooKeeper Atomic Broadcast) protocol** — two-phase commit: proposal write to ZK log → concurrent quorum ACK collection
- **Leader election** — ZooKeeper Curator `LeaderSelector` for automatic failover
- **Forward-to-leader** — follower nodes HTTP-forward proposals to the current leader
- **Partition detection** — reachable-node counting with quorum-based write gating
- **Proposal status tracking** — each proposal transitions through PROPOSED → COMMITTED / ABORTED

---

## Prerequisites

- **Java 21+** (JDK)
- **Maven 3.9+** (or use the included `mvnw` wrapper)
- **Node.js 18+** and **npm 9+**
- **Apache ZooKeeper 3.8+** running on `localhost:2181`

---

## Quick Start

### 1. Start ZooKeeper

Download and run Apache ZooKeeper locally on the default port `2181`.

### 2. Backend (single node)

```bash
cd backend
./mvnw spring-boot:run
```

The API server starts on `http://localhost:8080` by default.

To run a **5-node cluster**, launch each node with a different port and node ID:

```bash
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8080 --cloudbox.node-id=1"
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081 --cloudbox.node-id=2"
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8082 --cloudbox.node-id=3"
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8083 --cloudbox.node-id=4"
./mvnw spring-boot:run "-Dspring-boot.run.arguments=--server.port=8084 --cloudbox.node-id=5"
```

### 3. Frontend

```bash
cd frontend
npm install
npm run dev
```

The dev server starts on `http://localhost:5173` with hot reload.  
The frontend automatically fails over across all 5 backend nodes (ports 8080–8084) if one is unreachable.

---

## REST API Endpoints

### File Operations
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/files/upload` | Upload a file (multipart) |
| GET | `/api/files/download?path=` | Download a file |
| GET | `/api/files/list?path=` | List files in a folder |
| DELETE | `/api/files/delete?path=` | Delete a file |
| GET | `/api/files/replication-status` | Per-file replica counts |

### Consensus & Cluster
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/cluster/consensus/status` | Full cluster status |
| GET | `/api/cluster/consensus/leader` | Current leader info |
| GET | `/api/cluster/consensus/partition` | Partition detection status |
| POST | `/api/cluster/consensus/propose` | Submit a ZAB proposal |
| POST | `/api/cluster/consensus/ack?proposalId=` | Follower ACK (internal) |
| GET | `/api/cluster/consensus/heartbeat` | Node liveness heartbeat |
| GET | `/api/cluster/consensus/health` | Consensus module health |

### Fault Tolerance
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/fault/status` | Fault status + MTTF/MTTR/Availability |
| POST | `/api/admin/simulate-failure?nodeId=` | Simulate node failure |
| POST | `/api/admin/simulate-recovery?nodeId=` | Simulate node recovery |

### Time Synchronization
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/timesync/status` | Sync status (HLC, Lamport, skew) |
| GET | `/api/timesync/skew-report` | Per-node clock-skew details |
| GET | `/api/timesync/time` | Current server time (used by Cristian's Algorithm) |

### Health
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Node health check |

---

## Frontend Pages

| Route | Page | Description |
|-------|------|-------------|
| `/` | Dashboard | File browser — upload, download, delete files |
| `/cluster` | Cluster Status | Node cards, ZAB consensus details, partition status, time sync |
| `/fault-tolerance` | Fault Tolerance | Node health, recovery tasks, MTTF/MTTR/Availability |
| `/replication` | Replication | Per-file replica counts, RF & quorum settings |
| `/time-sync` | Time Sync | HLC, Lamport clock, skew table, Cristian's Algorithm panel |

---

## Project Structure

```
CloudBox/
├── backend/                        # Spring Boot application
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/cloudbox/
│   │   │   │   ├── config/         # ClusterConfig, WebConfig, properties
│   │   │   │   ├── controller/     # REST controllers
│   │   │   │   ├── domain/         # Enums (ConsistencyModel, ReplicaSelection)
│   │   │   │   ├── model/          # DTOs (ApiResponse, ClusterStatus, etc.)
│   │   │   │   └── service/        # Port interfaces + adapters + managers
│   │   │   └── resources/          # application.properties
│   │   └── test/                   # Unit & integration tests
│   ├── data/                       # Per-node file storage (node-1/ … node-5/)
│   ├── pom.xml
│   └── mvnw / mvnw.cmd
├── frontend/                       # React + Vite application
│   ├── src/
│   │   ├── components/             # Header, Sidebar, FileCard, UploadModal
│   │   ├── pages/                  # Dashboard, ClusterView, FaultTolerance, etc.
│   │   └── services/api.js         # Axios client with multi-node failover
│   ├── vite.config.js
│   └── package.json
└── README.md
```

---

## Technical Specifications

| Parameter | Value |
|-----------|-------|
| **Cluster size** | 5 nodes (ports 8080–8084) |
| **Quorum** | 3 (tolerates 2 failures) |
| **Replication factor** | 5 (all nodes) |
| **Consistency** | Quorum writes + leader reads |
| **Consensus** | ZAB (ZooKeeper Atomic Broadcast) |
| **Time sync** | Cristian's Algorithm with RTT compensation |
| **Logical clocks** | Hybrid Logical Clock + Lamport timestamps |
| **Max file size** | 100 MB |
| **Folder support** | Yes (path-based) |

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Port / Adapter architecture** | Clean separation between domain logic and infrastructure; services depend on port interfaces, not implementations |
| **Spring Cloud ZooKeeper** | Manages leader election, cluster state, distributed config, and locks out of the box |
| **ZAB two-phase commit** | Ensures total-order broadcast: proposal → quorum ACK → commit/abort |
| **Cristian's Algorithm** | Simple, lecture-aligned NTP approach; measures RTT and compensates for network delay |
| **Quorum writes (W=3, N=5)** | Guarantees consistency under up to 2 node failures |
| **Frontend multi-node failover** | Axios interceptor rotates through all 5 ports on connection failure — transparent to the user |
| **Tailwind CSS** | Rapid UI development with utility classes; OneDrive-inspired interface |
| **Vite** | Fast dev server with HMR for React |

---