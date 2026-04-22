package com.loom.benchmark.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;

import com.loom.benchmark.config.AppConfig;
import com.loom.benchmark.model.AggregatedResponse;
import com.loom.benchmark.model.DataSourceResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core service that fans out N concurrent data source calls and aggregates the results.
 *
 * === DESIGN DECISION: Executor is a parameter, not a field ===
 * The executor is passed in at call time rather than injected as a Spring dependency.
 * This is the key architectural choice that keeps the business logic IDENTICAL for
 * both Phase 1 (classic) and Phase 2 (virtual threads) — only the caller changes.
 *
 * If we had injected @Qualifier("classicExecutor") here, we'd need two separate
 * service beans. Instead, the controller decides which executor to use and passes it in.
 *
 * === Thread Safety ===
 * This service is stateless: no instance fields are mutated during request processing.
 * CompletableFuture handles all thread coordination. Safe to use from any number of
 * concurrent request threads.
 *
 * === CompletableFuture Fan-Out Pattern ===
 * The pattern used here is:
 *   1. Submit all N tasks to the executor → get N CompletableFutures (non-blocking).
 *   2. Call .join() on each future to collect results (blocks until each completes).
 *
 * The total wall-clock time is determined by the SLOWEST single task (max latency),
 * not the sum of all tasks — because all N tasks run concurrently.
 *
 * For CLASSIC with pool size = N: all tasks start immediately → time ≈ max(delays).
 * For CLASSIC with pool size < N: excess tasks queue → time grows as tasks serialize.
 * For LOOM: all tasks always start immediately → time ≈ max(delays), regardless of N.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AggregatorService {

    private final DataSourceSimulator simulator;
    private final AppConfig config;

    /**
     * Aggregate results from {@code numSources} simulated data sources concurrently.
     *
     * @param numSources     number of concurrent data source calls to make
     * @param executor       the executor to use (classic thread pool or virtual-thread executor)
     * @param mode           label for the response: "CLASSIC" or "LOOM"
     * @param threadPoolSize configured pool size for display purposes; pass -1 for virtual threads
     * @return               aggregated response with all results and timing metadata
     */
    public AggregatedResponse aggregate(
            int numSources,
            ExecutorService executor,
            String mode,
            int threadPoolSize) {

        log.info("[{}] Starting aggregation of {} sources (pool size={})", mode, numSources, threadPoolSize);

        long overallStart = System.currentTimeMillis();

        // --- STEP 1: Fan out all tasks concurrently ---
        // supplyAsync submits the task to the given executor and returns immediately.
        // We collect all futures first before waiting on any of them — this is important!
        // If we called .join() inside the map, we'd process them sequentially.
        List<CompletableFuture<DataSourceResult>> futures = IntStream.range(0, numSources)
                .mapToObj(i -> {
                    String sourceId = "source-" + i;
                    return CompletableFuture.supplyAsync(
                            () -> simulator.fetch(
                                    sourceId,
                                    config.getSimulation().getMinDelayMs(),
                                    config.getSimulation().getMaxDelayMs()
                            ),
                            executor   // <-- THIS is the only difference between Classic and Loom
                    );
                })
                .collect(Collectors.toList());

        // --- STEP 2: Collect results ---
        // .join() blocks the calling thread until each future completes.
        // Because all futures were submitted before we start joining, they all run
        // concurrently — we just gather them here as they finish.
        //
        // Note: CompletableFuture.join() (unlike .get()) throws unchecked exceptions,
        // so we don't need a try/catch for ExecutionException here.
        List<DataSourceResult> results = futures.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.toList());

        long totalDurationMs = System.currentTimeMillis() - overallStart;

        // Compute the average individual source duration for observability.
        double avgSourceDurationMs = results.stream()
                .mapToLong(DataSourceResult::durationMs)
                .average()
                .orElse(0.0);

        // Round to 1 decimal place for cleaner output.
        double avgRounded = Math.round(avgSourceDurationMs * 10.0) / 10.0;

        log.info("[{}] Aggregation complete: {} sources in {}ms (avg per source: {}ms)",
                mode, results.size(), totalDurationMs, avgRounded);

        return new AggregatedResponse(
                mode,
                numSources,
                results.size(),
                totalDurationMs,
                avgRounded,
                threadPoolSize,
                results
        );
    }
}
