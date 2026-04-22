package com.loom.benchmark.model;

import java.util.Map;

/**
 * Point-in-time snapshot of JVM thread activity, split by thread type.
 *
 * Two data sources are combined here:
 *
 *   1. ThreadMXBean  — the authoritative source for PLATFORM thread counts and states.
 *      Lives in java.lang.management and is always available. Reports OS-level thread
 *      metrics: total live, daemon, peak, total started since JVM boot.
 *
 *   2. Thread.getAllStackTraces()  — returns all live threads (platform + virtual) as
 *      of Java 21. We iterate the keyset and use Thread.isVirtual() to separate them.
 *      This call acquires a safepoint which is slightly expensive; fine for a 1-second
 *      polling interval but not for tight hot-path instrumentation.
 *
 * Why split platform vs virtual?
 *   - Platform threads are OS resources. High counts → memory pressure, context-switch cost.
 *   - Virtual threads are JVM-managed. Counts in the thousands are fine; each is cheap.
 *   - Watching both during a load test makes the Loom advantage immediately tangible:
 *     virtual thread count spikes sharply while platform thread count stays flat.
 *
 * Nested records are used so the JSON response is naturally grouped:
 *   { "platform": {...}, "virtual": {...} }
 */
public record ThreadSnapshot(

        /** ISO-8601 timestamp of when this snapshot was captured */
        String timestamp,

        /** Platform (OS-backed) thread statistics from ThreadMXBean */
        PlatformStats platform,

        /** Virtual thread statistics from Thread.getAllStackTraces() scan */
        VirtualStats virtual,

        /** Sum of all live platform + virtual threads visible to the JVM */
        int grandTotal

) {

    /**
     * Platform thread statistics.
     *
     * All counts come from ThreadMXBean except byState which we compute ourselves
     * by scanning Thread.getAllStackTraces() for non-virtual threads.
     *
     * Key fields for load testing:
     *   - total: should stay near poolSize (20) during classic load, proving the ceiling.
     *   - peak:  high-water mark since JVM start. Monotonically increasing — never resets.
     *   - totalStartedEver: also monotonically increasing. (total - totalStartedEver) would
     *     show terminated threads if that were useful.
     */
    public record PlatformStats(
            /** Live platform threads right now */
            int total,
            /** Daemon threads (background, don't block JVM exit) */
            int daemon,
            /** Non-daemon threads (e.g. Tomcat request threads, classic pool threads) */
            int nonDaemon,
            /** Highest simultaneous platform thread count since JVM start */
            int peak,
            /** Cumulative platform threads created since JVM start */
            long totalStartedEver,
            /**
             * Live platform threads grouped by Thread.State.
             * RUNNABLE  → actively executing on CPU or runnable
             * WAITING   → indefinite block (Object.wait, LockSupport.park, …)
             * TIMED_WAITING → bounded block (Thread.sleep, wait(timeout), …)
             * BLOCKED   → trying to acquire a monitor lock (classic sync bottleneck)
             *
             * During classic I/O load: most threads will be TIMED_WAITING (inside sleep).
             */
            Map<String, Long> byState
    ) {}

    /**
     * Virtual thread statistics.
     *
     * Sourced from VirtualThreadTracker, NOT from Thread.getAllStackTraces().
     *
     * WHY a custom tracker instead of the JDK APIs?
     * Thread.getAllStackTraces() intentionally excludes virtual threads in Java 21.
     * ThreadMXBean.getThreadCount() also counts only platform threads.
     * The JDK made these choices because virtual threads can number in the millions —
     * enumerating all of them on demand would be unsafe. The fix is tracked by the JDK
     * team but not yet available in Java 21.
     *
     * VirtualThreadTracker wraps every task submitted to the loomExecutor and
     * increments/decrements an AtomicLong around each task's run() call.
     * This gives exact, real-time counts with zero per-poll overhead.
     *
     * Key insight for load testing:
     *   - Under Loom load, "active" spikes to the number of concurrent in-flight tasks.
     *   - All those virtual threads are sleeping (TIMED_WAITING state) but their carrier
     *     OS threads are NOT blocked — the JVM unmounts them during Thread.sleep().
     *   - After the burst, "active" drops back to 0 quickly.
     *   - "startedEver" is a monotonically increasing counter — useful for throughput math.
     */
    public record VirtualStats(
            /** Virtual threads currently inside a task body (started, not yet returned) */
            long active,
            /** Cumulative virtual threads started since JVM launch */
            long startedEver,
            /** Cumulative virtual threads that have finished (normally or with exception) */
            long completedEver
    ) {}
}
