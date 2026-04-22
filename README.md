# loom-benchmark

A Java 21 + Spring Boot 3.3 project that makes the Project Loom advantage **visible and measurable**. It runs a Data Aggregator Service that fans out N concurrent calls to simulated slow I/O sources, then compares how a classic fixed thread pool and virtual threads (Project Loom) handle the load — with live thread monitoring, memory snapshots, and k6 load tests.

---

## What this project demonstrates

| Concept | Where to look |
|---|---|
| Classic fixed thread pool (platform threads) | `ExecutorConfig.java` → `classicExecutor` |
| Virtual thread executor (Project Loom) | `ExecutorConfig.java` → `loomExecutor` |
| Identical business logic, swappable executor | `AggregatorService.java` → `aggregate()` |
| Thread name proof: `[platform]` vs `[virtual]` | `DataSourceSimulator.java` |
| Live platform + virtual thread counts | `GET /api/threads/snapshot` |
| JVM heap / GC / thread-stack memory stats | `GET /api/memory/snapshot` |
| k6 two-scenario load test with comparison table | `k6-benchmark.js` |
| Live terminal thread monitor | `watch-threads.sh` |
| Global virtual thread switch (Spring Boot 3.2+) | `application.yml` → `spring.threads.virtual.enabled` |

---

## Requirements

| Tool | Version |
|---|---|
| Java | 21+ |
| Maven | 3.8+ |
| k6 | 0.46+ (for load tests) |
| curl + python3 | any (for `watch-threads.sh`) |

---

## Quick start

```bash
# Clone and build
git clone https://github.com/YOUR_USERNAME/loom-benchmark.git
cd loom-benchmark
mvn clean install

# Run (default port 8080)
mvn spring-boot:run

# Or on a different port
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=9090"
```

---

## REST Endpoints

### Aggregation

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/aggregate/classic?sources=N` | Fan-out using fixed thread pool (20 platform threads) |
| GET | `/api/aggregate/loom?sources=N` | Fan-out using virtual threads (one per task, no limit) |
| GET | `/api/aggregate/compare?sources=N` | Runs both sequentially — side-by-side response |

Default `sources = 20`. Try values from 5 to 500 to stress the classic pool.

### Observability

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/threads/snapshot` | Live platform + virtual thread counts and state breakdown |
| GET | `/api/memory/snapshot` | JVM heap, non-heap, per-pool usage, GC stats, thread-stack estimate |
| GET | `/actuator/health` | Spring Boot health check |
| GET | `/actuator/metrics` | JVM and HTTP metrics (Micrometer) |

---

## Sample responses

### `/api/aggregate/loom?sources=5`

```json
{
  "mode": "LOOM",
  "sourcesRequested": 5,
  "sourcesCompleted": 5,
  "totalDurationMs": 743,
  "averageSourceDurationMs": 512.4,
  "threadPoolSize": -1,
  "results": [
    { "sourceId": "source-0", "data": "mock payload from source-0", "durationMs": 623, "threadName": "virtual-thread-0 [virtual]" },
    { "sourceId": "source-1", "data": "mock payload from source-1", "durationMs": 341, "threadName": "virtual-thread-1 [virtual]" }
  ]
}
```

- `threadPoolSize: -1` → no fixed pool limit (virtual threads)
- `threadName` suffix is the core proof: `[platform]` vs `[virtual]`

### `/api/threads/snapshot`

```json
{
  "timestamp": "2026-04-22T06:30:00.000Z",
  "platform": {
    "total": 23,
    "daemon": 18,
    "nonDaemon": 5,
    "peak": 25,
    "totalStartedEver": 31,
    "byState": { "RUNNABLE": 3, "TIMED_WAITING": 18, "WAITING": 2, "BLOCKED": 0 }
  },
  "virtual": {
    "active": 200,
    "startedEver": 4800,
    "completedEver": 4600
  },
  "grandTotal": 223
}
```

### `/api/memory/snapshot`

```json
{
  "timestamp": "2026-04-22T06:30:00.000Z",
  "heap": { "usedBytes": 52428800, "committedBytes": 134217728, "maxBytes": 536870912, "usedPct": 9.77 },
  "nonHeap": { "usedBytes": 71303168, "committedBytes": 73924608, "maxBytes": -1, "usedPct": null },
  "pools": [ { "name": "G1 Eden Space", "type": "HEAP", "usage": { ... } }, "..." ],
  "gc": [ { "name": "G1 Young Generation", "collectionCount": 4, "collectionTimeMs": 38 } ],
  "threadMemory": {
    "platformThreadCount": 23,
    "stackSizeBytesPerThread": 524288,
    "estimatedTotalStackBytes": 12058624,
    "note": "Estimate only. Virtual threads use ~1 KB initial stack (freed when parked), not counted here."
  }
}
```

---

## Experiments

### Experiment 1 — Sources ≤ pool size (no queuing)
```bash
curl "http://localhost:9090/api/aggregate/compare?sources=20"
```
Both classic and loom finish in ~800 ms. All 20 tasks fit in the pool — no queuing.

### Experiment 2 — Sources >> pool size (queuing kicks in)
```bash
curl "http://localhost:9090/api/aggregate/compare?sources=100"
```

| Mode | Expected duration | Why |
|---|---|---|
| Classic | ~4,000 ms | 100 tasks ÷ 20 threads = 5 batches × 800 ms |
| Loom | ~800 ms | All 100 virtual threads start immediately, sleep concurrently |

### Experiment 3 — Observe thread names
In the `results` array:
- Classic: `"classic-pool-4 [platform]"` — a real OS thread consuming ~512 KB stack
- Loom: `"virtual-thread-12 [virtual]"` — a JVM-managed thread consuming ~1 KB initial stack

### Expected latency table

| sources | Classic `totalDurationMs` | Loom `totalDurationMs` |
|---|---|---|
| 20 | ~800 ms | ~800 ms |
| 50 | ~2,400 ms | ~800 ms |
| 100 | ~4,000 ms | ~800 ms |
| 200 | ~8,000 ms | ~800 ms |

---

## Load testing with k6

```bash
# Default: 200 VUs, 30s each scenario, sources=20
k6 run k6-benchmark.js

# Custom parameters
k6 run -e VUS=100 -e DURATION=20s -e SOURCES=50 -e HOST=http://localhost:9090 k6-benchmark.js
```

The script runs two scenarios back-to-back:
1. **classic** (0s–30s) — hammers `/api/aggregate/classic`
2. **loom** (40s–70s) — hammers `/api/aggregate/loom`

At the end, a summary table is printed:

```
╔══════════════════════════════════════════════════════════════╗
║          loom-benchmark  —  k6 Load Test Summary            ║
╠══════════════════╦══════════════════╦═══════════════════════╣
║ Metric           ║ CLASSIC          ║ LOOM                  ║
╠══════════════════╬══════════════════╬═══════════════════════╣
║ p50 latency      ║ 1823ms           ║ 743ms                 ║
║ p95 latency      ║ 6201ms           ║ 798ms                 ║
║ p99 latency      ║ 9847ms           ║ 812ms                 ║
║ max latency      ║ 14920ms          ║ 834ms                 ║
║ error rate       ║ 0.00%            ║ 0.00%                 ║
╚══════════════════╩══════════════════╩═══════════════════════╝
```

**Thresholds** (fail the run if violated):
- `loom_error_rate < 1%`
- `loom_req_duration p(95) < 2000ms`
- `classic_req_duration p(95) < 10000ms`

---

## Live thread monitor

Run this in a second terminal **while** k6 is running:

```bash
chmod +x watch-threads.sh
./watch-threads.sh

# Custom host or polling interval
HOST=http://localhost:9090 INTERVAL=0.5 ./watch-threads.sh
```

You'll see a live ANSI table refreshing every second showing:
- Platform thread count and state breakdown (RUNNABLE / TIMED_WAITING / WAITING / BLOCKED)
- Virtual thread active count with fill bar
- `startedEver` and `completedEver` counters

**What to watch:**
- During classic load → `TIMED_WAITING` fills up to ~20 (pool ceiling), then stalls
- During loom load → `VIRTUAL active` spikes into the hundreds while platform counts barely move

---

## Memory comparison workflow

Use `/api/memory/snapshot` to capture before/during/after snapshots:

```bash
# Baseline (no load)
curl -s http://localhost:9090/api/memory/snapshot > memory-baseline.json

# Start k6 classic phase, then snapshot mid-run
curl -s http://localhost:9090/api/memory/snapshot > memory-classic.json

# Start k6 loom phase, then snapshot mid-run
curl -s http://localhost:9090/api/memory/snapshot > memory-loom.json
```

**What to compare:**
| Field | Classic vs Loom |
|---|---|
| `heap.usedBytes` | Classic: higher (more live task objects queued) |
| `gc.collectionCount` | Classic: more GC cycles under sustained load |
| `threadMemory.estimatedTotalStackBytes` | Classic: grows with platform thread count (512 KB each) |
| `virtual.active` (from `/api/threads/snapshot`) | Loom: spikes high; stacks freed when threads park |

---

## Phase 3 — Global virtual thread switch (Spring Boot 3.2+)

In `application.yml`:
```yaml
spring:
  threads:
    virtual:
      enabled: true   # Change from false
```

This replaces **all** internal thread pools — Tomcat request threads, `@Async` executors, scheduling — with virtual threads. Zero code changes required.

**Effect:** Even the `/classic` endpoint performs better because Tomcat itself now handles HTTP connections on virtual threads. The classic `ThreadPoolExecutor` inside still uses platform threads, but the surrounding infrastructure no longer blocks on them.

---

## Project structure

```
loom-benchmark/
├── pom.xml
├── application.yml
├── k6-benchmark.js              # k6 two-scenario load test
├── watch-threads.sh             # Live terminal thread monitor
├── loadtest.sh                  # Apache Bench load test script
└── src/main/java/com/loom/benchmark/
    ├── LoomBenchmarkApplication.java
    ├── config/
    │   └── AppConfig.java               # Typed YAML config (@ConfigurationProperties)
    ├── controller/
    │   ├── AggregatorController.java    # /api/aggregate/* endpoints
    │   ├── ThreadController.java        # /api/threads/snapshot
    │   └── MemoryController.java        # /api/memory/snapshot
    ├── executor/
    │   ├── ExecutorConfig.java          # classicExecutor + loomExecutor beans
    │   └── VirtualThreadTracker.java    # AtomicLong counters for virtual threads
    ├── model/
    │   ├── DataSourceResult.java        # Result from one simulated source
    │   ├── AggregatedResponse.java      # Fan-out response
    │   ├── ThreadSnapshot.java          # Platform + virtual thread counts
    │   └── MemorySnapshot.java          # Heap, GC, pool, thread-stack stats
    └── service/
        ├── AggregatorService.java       # Fan-out + collect (executor as parameter)
        └── DataSourceSimulator.java     # Simulates blocking I/O with Thread.sleep
```

---

## Key design decisions

**1. Executor as a method parameter (not a field)**
`AggregatorService.aggregate()` receives the `ExecutorService` at call time. Both phases use identical business logic — the controller simply passes a different executor. No duplication.

**2. VirtualThreadTracker for accurate virtual thread counts**
`Thread.getAllStackTraces()` and `ThreadMXBean` intentionally exclude virtual threads in Java 21 (enumerating millions would be unsafe). The tracker wraps every submitted `Runnable` with `AtomicLong` increment/decrement — exact counts, zero per-poll overhead.

**3. Named thread factories**
Both executors use named factories (`classic-pool-N`, `virtual-thread-N`) so every `DataSourceResult.threadName` in the response proves which type of thread handled the task.

**4. CompletableFuture fan-out**
All futures are submitted before any `.join()` is called. Sequential joins inside the stream would serialize all calls and negate the concurrency.

**5. Bounded queue on classic executor**
`LinkedBlockingQueue` with a capacity limit shows the back-pressure reality of fixed pools. Under extreme load you'll get `RejectedExecutionException` — intentional. Size it with: `VUs × sources − poolSize`.

---

## Why virtual threads win for I/O-bound work

```
Classic:   OS Thread  →  blocks on sleep/I/O  →  OS thread is held, can't do other work
Loom:      Virtual Thread  →  parks on sleep/I/O  →  carrier OS thread is RELEASED, picks up another virtual thread
```

Virtual threads are not faster per se — they let the JVM run far more concurrent I/O tasks with the same number of OS threads. The mental model:

> `Thread.sleep()` on a virtual thread behaves like `await asyncio.sleep()` in Python or `await Task.Delay()` in C# — it yields the carrier thread, not blocks it.

---

## License

MIT
