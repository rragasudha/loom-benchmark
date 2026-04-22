package com.loom.benchmark.model;

import java.util.List;

/**
 * Point-in-time snapshot of JVM memory usage.
 *
 * Data sources:
 *   - MemoryMXBean      — heap and non-heap totals
 *   - MemoryPoolMXBean  — per-pool breakdown (Eden, Survivor, Old Gen, Metaspace, etc.)
 *   - GarbageCollectorMXBean — GC invocation counts and cumulative pause time
 *
 * Use this endpoint before and after a load test run to compare how classic
 * (platform thread) vs Loom (virtual thread) workloads affect heap pressure.
 * Virtual threads are stack-less when parked, so heap growth under Loom load
 * is driven purely by live task data — not by thread stacks.
 */
public record MemorySnapshot(

        /** ISO-8601 timestamp of when this snapshot was captured */
        String timestamp,

        /** JVM heap memory (Eden + Survivor + Old Gen) */
        MemoryUsage heap,

        /** Non-heap memory (Metaspace, Code Cache, Compressed Class Space) */
        MemoryUsage nonHeap,

        /** Breakdown by individual memory pool */
        List<MemoryPoolInfo> pools,

        /** Garbage collector statistics */
        List<GcInfo> gc,

        /** Rough estimate of memory consumed by live platform threads (stack space) */
        ThreadMemoryEstimate threadMemory

) {

    /**
     * Memory usage for a region (heap or non-heap, or a specific pool).
     *
     * All values in bytes. Percentages computed here for convenience.
     *
     *   used      — bytes currently occupied by live objects
     *   committed — bytes the JVM has reserved from the OS (may be larger than used)
     *   max       — upper bound (-1 if undefined, e.g. Metaspace without -XX:MaxMetaspaceSize)
     *   usedPct   — used / max × 100  (null when max == -1)
     */
    public record MemoryUsage(
            long usedBytes,
            long committedBytes,
            long maxBytes,
            Double usedPct
    ) {
        public static MemoryUsage of(java.lang.management.MemoryUsage mu) {
            long max = mu.getMax();
            Double pct = (max > 0) ? (mu.getUsed() * 100.0 / max) : null;
            return new MemoryUsage(mu.getUsed(), mu.getCommitted(), max, pct);
        }
    }

    /** Stats for one named memory pool (e.g. "G1 Eden Space", "Metaspace"). */
    public record MemoryPoolInfo(
            String name,
            String type,        // HEAP or NON_HEAP
            MemoryUsage usage
    ) {}

    /**
     * Cumulative GC statistics since JVM start.
     *
     * collectionCount — total GC cycles run (minor + major combined per collector)
     * collectionTimeMs — total wall-clock pause time attributed to this collector
     *
     * A spike in collectionCount or collectionTimeMs between two snapshots
     * (one taken during classic load, one during Loom load) reveals whether
     * the workload caused GC pressure.
     */
    public record GcInfo(
            String name,
            long collectionCount,
            long collectionTimeMs
    ) {}

    /**
     * Rough estimate of OS memory held by platform thread stacks.
     *
     * Each platform thread reserves a stack (default 512 KB on most JVMs; tunable
     * with -Xss). Virtual threads use a much smaller initial stack (~1 KB) that
     * grows on demand and is freed when the thread parks.
     *
     * This is an ESTIMATE — actual RSS depends on OS, JVM flags, and stack depth.
     */
    public record ThreadMemoryEstimate(
            int platformThreadCount,
            long stackSizeBytesPerThread,
            long estimatedTotalStackBytes,
            String note
    ) {}
}
