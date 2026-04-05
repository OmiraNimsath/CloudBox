# CloudBox - Fault-Tolerant Distributed File Storage System

A distributed file storage system built for the **Distributed Systems** module. The system ensures high availability, fault tolerance, and consistency while handling concurrent read/write operations from multiple clients across multiple servers. Files uploaded to any node are immediately replicated across the 5-node cluster using quorum-based replication, and the system remains operational even when up to 2 nodes fail.

---

## Architecture Overview

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 21, Spring Boot 3.x, Maven |
| **Cluster Coordination** | Apache ZooKeeper 3.8+ (via Spring Cloud Zookeeper / Apache Curator) |
| **Frontend** | React 19, Vite 6, Tailwind CSS 4 |
| **Architecture Pattern** | Layered (Controller → Service → Infrastructure) |

### System Components

| Component | Algorithm / Mechanism | Member |
|-----------|----------------------|--------|
| Fault Tolerance | Heartbeat monitoring (3 s interval, 3-miss threshold), MTTF/MTTR/Availability metrics, self-healing re-replication, tombstone deletes | IT24103437 |
| Data Replication & Consistency | Quorum writes (W=3), quorum reads (R=3), last-write-wins via Lamport timestamps, pending-delete tombstones | IT24103439 |
| Time Synchronization | Berkeley Algorithm (master polls slaves, RTT-compensated, pushes correction δᵢ = avgTime - nodeTimeᵢ), Hybrid Logical Clock (HLC), Lamport timestamps | IT24101495 |
| Consensus & Agreement | ZAB (ZooKeeper Atomic Broadcast) via Curator LeaderSelector - ephemeral sequential znodes, epoch/ZXID ordering, partition-aware write gating | IT24101842 |

---

## Team Members

| Role | Registration No. | Email |
|------|-------------------|-------|
| Member 1 - Fault Tolerance | IT24103437 | it24103437@my.sliit.lk |
| Member 2 - Replication & Consistency | IT24103439 | it24103439@my.sliit.lk |
| Member 3 - Time Synchronization | IT24101495 | it24101495@my.sliit.lk |
| Member 4 - Consensus & Agreement | IT24101842 | it24101842@my.sliit.lk |

---

## Implemented Features

### Fault Tolerance (IT24103437)
- **Heartbeat-based failure detection** - every node pings all peers every 3 s; 3 consecutive missed heartbeats marks a node `UNHEALTHY`
- **Self-healing re-replication** - a scheduled task (every 5 s) pushes any locally-held file to peer nodes that are missing it, restoring full replication automatically after a node recovers
- **Tombstone delete propagation** - deletes that occurred while a node was down are queued as tombstones and applied as soon as the node comes back online
- **Broadcast failure/recovery API** - simulating a failure or recovery on any node propagates immediately to all peers so the whole cluster view stays consistent
- **MTTF / MTTR / Availability metrics** - real-time reliability calculations (Mean Time To Failure, Mean Time To Repair, Availability = MTTF/(MTTF+MTTR)) exposed via REST and persisted across restarts
- **Simulate failure / recovery** - admin endpoints inject and resolve node failures for live demonstration

### Data Replication & Consistency (IT24103439)
- **Quorum writes (W=3, N=5)** - a write is acknowledged only when at least 3 of 5 replicas confirm, guaranteeing durability through up to 2 simultaneous failures
- **Quorum reads (R=3)** - reads require at least 3 nodes to confirm file presence, preventing stale reads from isolated nodes
- **W + R = N > 5** - write quorum and read quorum always share at least 1 common node, ensuring read-your-writes consistency
- **Replication factor 5** - every file is replicated to all nodes for maximum availability
- **Last-write-wins conflict resolution** - Lamport logical timestamps decide which version survives concurrent writes to the same file
- **Pending-delete tombstones** - tracked per-node so deletes during downtime are applied on recovery
- **Replication status API** - per-file replica count and node distribution visible from the dashboard

### Time Synchronization (IT24101495)
- **Berkeley Algorithm** - active push-based distributed time synchronization:
  1. The node with the lowest alive ID elects itself master each round (deterministic, no coordination needed)
  2. Master polls all slaves via `GET /api/timesync/time`, compensating for RTT (`peerTime + rtt/2`)
  3. Master computes cluster average: `avgTime = mean(allCollectedTimes)`
  4. Master pushes individual correction deltas to every node: `δᵢ = avgTime − nodeTimeᵢ`
  5. Each node shifts its accumulated correction offset by `δᵢ`
  6. Rounds execute every 10 s; master broadcasts the full round summary so all nodes display consistent metrics
- **Clock-skew detection** - per-node skew monitoring with configurable alert threshold (default 100 ms); `synced` flag set to `false` when fewer than 2 nodes respond
- **Hybrid Logical Clock (HLC)** - combines physical time with a logical counter to guarantee causal ordering even when physical clocks drift
- **Lamport timestamps** - strictly increasing logical counter used for last-write-wins conflict resolution in replication
- **Persistence** - accumulated Berkeley correction and Lamport clock value are saved to disk and restored on restart so nodes don't lose synchronization state

### Consensus & Agreement (IT24101842)
- **ZAB (ZooKeeper Atomic Broadcast)** - consensus via Apache Curator `LeaderSelector`:
  - Participants write ephemeral sequential znodes under `/cloudbox/election`
  - The node holding the lowest sequence number becomes leader
  - On leader crash ZooKeeper removes the ephemeral znode; the next-lowest node wins the re-election within ~10 s (session timeout + election overhead)
- **Epoch / ZXID ordering** - `electionEpoch` (wall-clock ms of leadership acquisition) and `zxid` (monotonically increasing transaction ID) guarantee total order of all state changes
- **Leader heartbeat** - leader broadcasts epoch + zxid to all followers every 3 s; followers accept and advance their own ZXID
- **Partition detection** - if reachable nodes < quorum (3), writes are blocked cluster-wide to prevent split-brain
- **Auto-requeue** - Curator `autoRequeue` ensures followers automatically re-enter the election when they lose leadership

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
| POST | `/api/files/upload` | Upload a file (multipart/form-data) |
| GET | `/api/files/download?path=` | Download a file by path |
| GET | `/api/files/list?path=` | List files in a folder |
| DELETE | `/api/files/delete?path=` | Delete a file |
| GET | `/api/files/replication-status` | Per-file replica counts and distribution |

### Consensus & Leader Election
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/consensus/status` | Full ZAB consensus state (leader, epoch, ZXID, quorum, partition) |

### Fault Tolerance
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/fault/status` | Cluster health: node statuses, MTTF/MTTR/Availability, under-replicated files |
| POST | `/api/admin/simulate-failure?nodeId=` | Simulate a node failure (broadcasts to all peers) |
| POST | `/api/admin/simulate-recovery?nodeId=` | Simulate a node recovery (broadcasts to all peers) |

### Time Synchronization
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/timesync/status` | Full Berkeley sync state (HLC, Lamport, correction, round info) |
| GET | `/api/timesync/time` | Raw epoch millis - polled by Berkeley master for RTT measurement |
| POST | `/api/timesync/correct?delta=` | Berkeley master pushes correction delta to this slave |
| POST | `/api/timesync/round-summary` | Berkeley master broadcasts full round summary to all nodes |
| GET | `/api/timesync/skew-report` | Per-node correction deltas and peak skew from last round |

### Internal (node-to-node)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Node liveness - used by heartbeat monitor |
| POST | `/api/internal/replicate` | Accept a file replica from another node |
| HEAD | `/api/internal/replicate?fileId=` | Check whether this node holds a specific file |
| DELETE | `/api/internal/replicate?fileId=` | Delete a replica on this node |
| POST | `/api/internal/mark-failed?nodeId=` | Propagate simulated failure to this node |
| POST | `/api/internal/mark-recovered?nodeId=` | Propagate simulated recovery to this node |
| POST | `/api/internal/consensus-sync?epoch=&zxid=` | Leader pushes epoch/ZXID to followers |
| GET | `/api/internal/metrics-snapshot` | Export metrics for gossip startup sync |
| POST | `/api/internal/metrics-sync` | Import metrics snapshot from a peer |

---

## Frontend Pages

| Route | Page | Description |
|-------|------|-------------|
| `/` | Dashboard | File browser - upload, download, delete files |
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
│   │   │   │   ├── config/         # ClusterConfig (constants, RestTemplate), WebConfig (CORS)
│   │   │   │   ├── controller/     # REST controllers (File, Consensus, Fault, TimeSync, Admin)
│   │   │   │   ├── model/          # DTOs / records (ApiResponse, ConsensusStatus, FaultStatus, …)
│   │   │   │   └── service/        # Core algorithms (NodeRegistry, ReplicationService,
│   │   │   │                       #   TimeSyncService, ConsensusService)
│   │   │   └── resources/
│   │   │       └── application.properties
│   │   └── test/
│   ├── data/                       # Per-node file storage (node-1/ … node-5/) - git-ignored
│   ├── pom.xml
│   └── mvnw / mvnw.cmd
├── frontend/                       # React + Vite application
│   ├── src/
│   │   ├── components/             # Header, Sidebar, FileList, FileCard, UploadModal
│   │   ├── pages/                  # Dashboard, ClusterView, FaultTolerancePage,
│   │   │                           #   ReplicationPage, TimeSyncPage
│   │   └── services/api.js         # Axios client with automatic multi-node failover
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
| **Consistency** | Quorum writes + Quorum reads |
| **Consensus** | ZAB (ZooKeeper Atomic Broadcast) |
| **Time sync** | Berkeley Algorithm (RTT-compensated, push-based correction deltas) |
| **Logical clocks** | Hybrid Logical Clock + Lamport timestamps |
| **Max file size** | 100 MB |
| **Folder support** | Yes (path-based) |

---

## Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Berkeley Algorithm (not Cristian's)** | Berkeley is active/push-based — the master collects all peer times and pushes corrections; no single time-server authority is needed. This handles the case where any node can become master. |
| **ZAB via ZooKeeper Curator** | Curator `LeaderSelector` handles the full ephemeral-znode election lifecycle; ZAB's epoch/ZXID semantics give total-order broadcast without implementing Paxos/Raft from scratch. |
| **Quorum writes W=3, reads R=3, N=5** | W+R=6 ≥ N guarantees at least one node is in both the write and read sets, ensuring read-your-writes consistency while tolerating up to 2 failures. |
| **Full replication RF=5 (not erasure coding)** | Maximises read availability and simplifies the failure/recovery demonstration. Every node always has a complete copy. Trade-off: higher storage cost vs. erasure coding's lower overhead. |
| **Berkeley master = lowest alive node ID** | Deterministic election without ZooKeeper coordination - every node independently arrives at the same master ID using the same `min(aliveIds)` rule, avoiding split-brain from ZK unavailability. |
| **Self-healing every 5 s** | Fast post-recovery convergence. After a node rejoins, any missing replicas are pushed within 5 s without operator intervention. |
| **Tombstone deletes** | Deletes that arrive while a node is down are queued and applied on recovery, preventing ghost files from re-appearing after a rejoin. |
| **Metrics persistence to disk** | Lamport clock and Berkeley correction offset survive process restarts, so nodes don't reset their logical time or sync state on redeploy. |
| **Frontend multi-node failover** | Axios interceptor rotates through all 5 backend ports on a 503 or network error - transparent to the user. Initial probe timeout is 800 ms so failover is near-instant. |
| **Vite dev server** | Hot-module replacement for fast frontend iteration; production build outputs a static bundle. |

---

## Assignment Coverage

| Criterion (20% each) | Where Implemented | Key Classes |
|----------------------|-------------------|-------------|
| **Fault Tolerance** | Heartbeat-based failure detection (3 s / 3 miss), self-healing re-replication (5 s), tombstone deletes, MTTF/MTTR/Availability metrics, simulate-failure/recovery admin API | `NodeRegistry`, `ReplicationService.healUnderReplicatedFiles()`, `FaultController`, `AdminController` |
| **Data Replication & Consistency** | Quorum writes (W=3), quorum reads (R=3), last-write-wins (Lamport), pending-delete tombstones, per-file distribution status | `ReplicationService`, `FileController`, `ReplicationStatus` |
| **Time Synchronization** | Berkeley Algorithm (RTT-compensated, master=min-alive-ID), HLC, Lamport timestamps, skew alerts, round summary broadcast, persistence across restarts | `TimeSyncService`, `TimeSyncController` |
| **Consensus & Agreement** | ZAB via Curator LeaderSelector (ephemeral sequential znodes), epoch/ZXID total-order, leader heartbeat every 3 s, partition detection, quorum-gated writes | `ConsensusService`, `ConsensusController` |
| **Overall Integration** | File upload pipeline: HLC tick → Lamport tick → quorum replicate → ZXID increment → 5-node fan-out. Frontend shows all four modules live with 5 s auto-refresh. | All controllers + services wired together; `api.js` multi-node failover |

---