package com.loom.benchmark.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.loom.benchmark.config.AppConfig;
import com.loom.benchmark.model.AggregatedResponse;
import com.loom.benchmark.service.AggregatorService;

import lombok.extern.slf4j.Slf4j;

/**
 * REST controller exposing three endpoints for comparing classic threads vs Loom.
 *
 *   GET /api/aggregate/classic?sources=N   — uses fixed thread pool (Phase 1)
 *   GET /api/aggregate/loom?sources=N      — uses virtual threads (Phase 2)
 *   GET /api/aggregate/compare?sources=N   — calls both and returns side-by-side timing
 *
 * The controller's only job is to wire the right executor to the service call.
 * Business logic lives entirely in AggregatorService — this controller is thin by design.
 */
@Slf4j
@RestController
@RequestMapping("/api/aggregate")
public class AggregatorController {

    private final AggregatorService aggregatorService;
    private final ExecutorService classicExecutor;
    private final ExecutorService loomExecutor;
    private final AppConfig appConfig;

    /**
     * Constructor injection with @Qualifier to disambiguate the two ExecutorService beans.
     * Spring would fail to inject without @Qualifier because two beans match ExecutorService.
     */
    public AggregatorController(
            AggregatorService aggregatorService,
            @Qualifier("classicExecutor") ExecutorService classicExecutor,
            @Qualifier("loomExecutor") ExecutorService loomExecutor,
            AppConfig appConfig) {
        this.aggregatorService = aggregatorService;
        this.classicExecutor = classicExecutor;
        this.loomExecutor = loomExecutor;
        this.appConfig = appConfig;
    }

    /**
     * Phase 1 endpoint: aggregates using the classic fixed thread pool.
     *
     * Try: GET /api/aggregate/classic?sources=20
     * Then: GET /api/aggregate/classic?sources=200  (pool size is only 20 — observe queuing!)
     *
     * @param sources number of simulated data sources to fan out to (default: 20)
     */
    @GetMapping("/classic")
    public AggregatedResponse classic(
            @RequestParam(defaultValue = "20") int sources) {

        log.info("Classic endpoint called with sources={}", sources);

        // threadPoolSize is the configured pool size — used for display in the response.
        int poolSize = appConfig.getThreadPool().getCoreSize();

        return aggregatorService.aggregate(sources, classicExecutor, "CLASSIC", poolSize);
    }

    /**
     * Phase 2 endpoint: aggregates using Project Loom virtual threads.
     *
     * Try: GET /api/aggregate/loom?sources=200  (observe: same latency as sources=20!)
     *
     * @param sources number of simulated data sources to fan out to (default: 20)
     */
    @GetMapping("/loom")
    public AggregatedResponse loom(
            @RequestParam(defaultValue = "20") int sources) {

        log.info("Loom endpoint called with sources={}", sources);

        // threadPoolSize = -1 signals "virtual threads" in the response —
        // there is no fixed pool; every task gets its own virtual thread.
        return aggregatorService.aggregate(sources, loomExecutor, "LOOM", -1);
    }

    /**
     * Comparison endpoint: runs CLASSIC and LOOM sequentially and returns both results.
     *
     * NOTE: The two calls run sequentially (classic first, then loom). This means
     * the total HTTP response time is sum(classic) + sum(loom), but each individual
     * result's totalDurationMs is an accurate standalone measurement. This is the
     * fairest way to compare without interference between the two executor pools.
     *
     * Try: GET /api/aggregate/compare?sources=50
     * Observe: classic totalDurationMs >> loom totalDurationMs when sources > poolSize.
     *
     * @param sources number of simulated data sources for each run (default: 20)
     */
    @GetMapping("/compare")
    public Map<String, AggregatedResponse> compare(
            @RequestParam(defaultValue = "20") int sources) {

        log.info("Compare endpoint called with sources={}", sources);

        int poolSize = appConfig.getThreadPool().getCoreSize();

        AggregatedResponse classicResult =
                aggregatorService.aggregate(sources, classicExecutor, "CLASSIC", poolSize);

        AggregatedResponse loomResult =
                aggregatorService.aggregate(sources, loomExecutor, "LOOM", -1);

        // LinkedHashMap preserves insertion order so "classic" appears before "loom" in JSON.
        Map<String, AggregatedResponse> comparison = new LinkedHashMap<>();
        comparison.put("classic", classicResult);
        comparison.put("loom", loomResult);
        return comparison;
    }
}
