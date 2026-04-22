package com.loom.benchmark.service;

import java.util.Random;

import org.springframework.stereotype.Service;

import com.loom.benchmark.model.DataSourceResult;

import lombok.extern.slf4j.Slf4j;

/**
 * Simulates a slow external data source (e.g. a microservice, database, or third-party API).
 *
 * The simulation uses Thread.sleep() to represent blocking I/O. This is the exact pattern
 * where Project Loom delivers its performance advantage:
 *
 *   - CLASSIC mode: Thread.sleep() holds the OS thread hostage for the full delay.
 *     That OS thread cannot serve any other request until it wakes up.
 *
 *   - LOOM mode: Thread.sleep() on a virtual thread causes the JVM to UNMOUNT the
 *     virtual thread from its carrier (OS) thread. The carrier is immediately free
 *     to run other virtual threads. When the sleep expires, the virtual thread is
 *     re-mounted and resumes — transparent to the code.
 *
 * This class is intentionally stateless (beyond the Random instance) so it is
 * safely shareable across all concurrent callers without synchronization.
 */
@Slf4j
@Service
public class DataSourceSimulator {

    // Random is not thread-safe; in Java 17+ ThreadLocalRandom is preferred for
    // concurrent use. However, since each task runs on its own thread (classic or
    // virtual), using a single Random with a small race window is acceptable here.
    // For production: replace with ThreadLocalRandom.current().nextInt(min, max).
    private final Random random = new Random();

    /**
     * Simulates fetching data from an external source.
     *
     * Steps:
     *   1. Record start time and current thread name (before blocking).
     *   2. Sleep for a random duration between [minDelayMs, maxDelayMs].
     *   3. Return a DataSourceResult with timing and thread metadata.
     *
     * Thread safety: This method is pure — it has no shared mutable state beyond
     * the weakly-synchronized Random. All local variables are stack-allocated and
     * therefore isolated per thread/virtual-thread.
     *
     * @param sourceId    identifier for this data source (e.g. "source-3")
     * @param minDelayMs  minimum simulated I/O latency in milliseconds
     * @param maxDelayMs  maximum simulated I/O latency in milliseconds
     * @return            result containing payload, timing, and thread metadata
     */
    public DataSourceResult fetch(String sourceId, int minDelayMs, int maxDelayMs) {
        long startMs = System.currentTimeMillis();

        // === CRITICAL LEARNING POINT ===
        // Capture the thread name and virtual status BEFORE the sleep.
        // In virtual thread mode the JVM may (theoretically) resume on a different
        // carrier after unmount/remount, but the virtual thread object and its name
        // remain stable throughout. This line proves which thread was assigned the task.
        Thread currentThread = Thread.currentThread();
        String threadName = currentThread.getName();
        boolean isVirtual = currentThread.isVirtual(); // Java 21 API

        // Annotate the name so the response visually distinguishes virtual from platform.
        String annotatedThreadName = isVirtual
                ? threadName + " [virtual]"
                : threadName + " [platform]";

        // Compute a random delay within the configured range.
        int delayMs = minDelayMs + random.nextInt(maxDelayMs - minDelayMs + 1);

        log.debug("[{}] Starting fetch on '{}' — sleeping {}ms", sourceId, annotatedThreadName, delayMs);

        try {
            // === THE BLOCKING CALL ===
            // Classic: pins the OS thread for `delayMs` ms.
            // Loom:    parks the virtual thread; carrier OS thread is freed immediately.
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            // Restore the interrupt flag so callers can react to cancellation.
            // Do NOT swallow InterruptedException — that suppresses shutdown signals.
            Thread.currentThread().interrupt();
            log.warn("[{}] Interrupted during fetch simulation", sourceId);
        }

        long durationMs = System.currentTimeMillis() - startMs;

        log.debug("[{}] Completed in {}ms on '{}'", sourceId, durationMs, annotatedThreadName);

        return new DataSourceResult(
                sourceId,
                "mock payload from " + sourceId,
                durationMs,
                annotatedThreadName
        );
    }
}
