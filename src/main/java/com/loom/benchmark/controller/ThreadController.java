package com.loom.benchmark.controller;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.loom.benchmark.executor.VirtualThreadTracker;
import com.loom.benchmark.model.ThreadSnapshot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Exposes a live thread-count snapshot endpoint, split by platform vs virtual thread type.
 *
 * Endpoint: GET /api/threads/snapshot
 *
 * Intended for use with the watch-threads.sh polling script during load tests.
 * Polling every second lets you see in real time:
 *
 *   CLASSIC load test → platform TIMED_WAITING climbs to pool-size (20) and saturates;
 *                       virtual.active stays 0.
 *
 *   LOOM load test    → virtual.active spikes to concurrent in-flight task count;
 *                       platform thread count barely moves (only carrier threads are platform).
 *
 * Data sources used:
 *
 *   ThreadMXBean       — authoritative for platform thread counts and lifecycle stats.
 *                        Uses JVM-internal counters; cheap to call at any rate.
 *
 *   Thread.getAllStackTraces()
 *                      — used ONLY for platform thread state breakdown (byState).
 *                        This API intentionally excludes virtual threads in Java 21
 *                        to avoid the cost of enumerating potentially millions of them.
 *                        We still call it for the platform state histogram — it's
 *                        accurate for platform threads and cheap at our poll rate.
 *
 *   VirtualThreadTracker
 *                      — our custom counter bean. Wraps every task submitted to the
 *                        loomExecutor with onStart()/onComplete() calls. Gives exact
 *                        live virtual thread counts with zero per-poll scanning cost.
 */
@Slf4j
@RestController
@RequestMapping("/api/threads")
@RequiredArgsConstructor
public class ThreadController {

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    private final VirtualThreadTracker virtualThreadTracker;

    @GetMapping("/snapshot")
    public ThreadSnapshot snapshot() {
        String timestamp = TS_FORMAT.format(Instant.now());

        // ---- 1. Platform thread info from JMX (cheap, always accurate) ----------
        int platformTotal = THREAD_MX.getThreadCount();
        int daemonCount   = THREAD_MX.getDaemonThreadCount();
        int peakCount     = THREAD_MX.getPeakThreadCount();
        long totalStarted = THREAD_MX.getTotalStartedThreadCount();

        // ---- 2. Platform thread state breakdown from a full thread scan ---------
        // Thread.getAllStackTraces() only returns platform threads in Java 21,
        // so we use it here purely to compute the byState histogram for platform threads.
        // We immediately discard the stack trace arrays (only the keyset is needed).
        Map<String, Long> platformByState = new LinkedHashMap<>();
        for (String state : new String[]{"RUNNABLE", "WAITING", "TIMED_WAITING", "BLOCKED"}) {
            platformByState.put(state, 0L);
        }
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.isVirtual()) {
                platformByState.merge(t.getState().name(), 1L, Long::sum);
            }
        }
        // Remove zero entries that aren't the core 4 states, to keep JSON lean.
        platformByState = platformByState.entrySet().stream()
                .filter(e -> e.getValue() > 0
                        || "RUNNABLE WAITING TIMED_WAITING BLOCKED".contains(e.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, Long::sum, LinkedHashMap::new));

        // ---- 3. Virtual thread counts from our custom tracker -------------------
        // VirtualThreadTracker.track(runnable) wraps every task submitted to loomExecutor,
        // bracketing the run() body with onStart()/onComplete(). The AtomicLong counters
        // here are therefore exact and never stale — no scanning required.
        long virtualActive    = virtualThreadTracker.getActive();
        long virtualStarted   = virtualThreadTracker.getStartedEver();
        long virtualCompleted = virtualThreadTracker.getCompletedEver();

        ThreadSnapshot.PlatformStats platform = new ThreadSnapshot.PlatformStats(
                platformTotal,
                daemonCount,
                platformTotal - daemonCount,
                peakCount,
                totalStarted,
                platformByState
        );

        ThreadSnapshot.VirtualStats virtual = new ThreadSnapshot.VirtualStats(
                virtualActive,
                virtualStarted,
                virtualCompleted
        );

        log.debug("Thread snapshot: platform={} virtual.active={}", platformTotal, virtualActive);

        return new ThreadSnapshot(
                timestamp,
                platform,
                virtual,
                (int) (platformTotal + virtualActive)
        );
    }
}
