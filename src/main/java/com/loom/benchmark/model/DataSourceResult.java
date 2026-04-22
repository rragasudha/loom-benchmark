package com.loom.benchmark.model;

/**
 * Result returned from a single simulated data source fetch.
 *
 * The most important fields for the learning demo are:
 *   - durationMs  : how long this particular call blocked (random 200-800 ms)
 *   - threadName  : which thread serviced this call
 *
 * In CLASSIC mode, threadName looks like: "classic-pool-3"
 * In LOOM mode,    threadName looks like: "virtual-thread-3 [virtual]"
 *
 * This difference is the core visual proof that virtual threads are being used.
 *
 * We use a Java 16+ record here: records are immutable, auto-generate equals/hashCode/toString,
 * and are ideal for plain data carriers like this.
 */
public record DataSourceResult(
        /** Identifier of the simulated data source, e.g. "source-0" */
        String sourceId,

        /** The mock payload that would normally come from an external API */
        String data,

        /** Wall-clock time this single fetch took, including the simulated I/O sleep */
        long durationMs,

        /**
         * Name of the thread that executed this fetch.
         * This is captured BEFORE Thread.sleep() so it always reflects the
         * thread assigned by the executor, not a post-sleep continuation thread.
         *
         * Classic: "classic-pool-N"
         * Loom:    "virtual-thread-N [virtual]"
         */
        String threadName
) {}
