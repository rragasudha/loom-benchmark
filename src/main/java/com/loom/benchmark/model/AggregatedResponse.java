package com.loom.benchmark.model;

import java.util.List;

/**
 * The final aggregated response returned by the REST API.
 *
 * Key fields for comparing CLASSIC vs LOOM:
 *   - totalDurationMs        : wall-clock time for ALL sources to complete concurrently.
 *                              With 20 sources each sleeping 200-800 ms, the ideal concurrent
 *                              execution finishes in ~max(individual delays), not sum.
 *                              Classic mode with a pool of 20 threads can handle 20 concurrent
 *                              tasks, so totalDurationMs ≈ max single delay (~800 ms).
 *                              Under heavy load (sources > poolSize), classic will queue and
 *                              totalDurationMs grows. Loom stays flat because each task gets
 *                              its own virtual thread.
 *
 *   - threadPoolSize         : -1 signals "virtual threads" (unbounded by design).
 *                              For classic, this is the configured fixed pool size.
 *
 *   - results                : individual results including per-thread names — the smoking gun
 *                              showing "classic-pool-N" vs "virtual-thread-N [virtual]".
 */
public record AggregatedResponse(
        /** "CLASSIC" or "LOOM" — which executor strategy was used */
        String mode,

        /** How many data sources were requested */
        int sourcesRequested,

        /** How many data sources actually completed (should equal sourcesRequested) */
        int sourcesCompleted,

        /** Total wall-clock time from fan-out start to last result received, in milliseconds */
        long totalDurationMs,

        /** Average individual source duration across all completed results, in milliseconds */
        double averageSourceDurationMs,

        /**
         * Size of the thread pool used.
         * Classic: fixed pool size (e.g. 20).
         * Loom:    -1, meaning "one virtual thread per task" — effectively unbounded.
         */
        int threadPoolSize,

        /** Individual results from each simulated data source */
        List<DataSourceResult> results
) {}
