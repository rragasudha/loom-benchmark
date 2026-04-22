package com.loom.benchmark.executor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.loom.benchmark.config.AppConfig;

/**
 * ============================================================
 *  PLATFORM THREADS vs VIRTUAL THREADS — KEY CONCEPTS
 * ============================================================
 *
 *  PLATFORM THREADS (classic OS threads):
 *  ----------------------------------------
 *  - Each Java platform thread maps 1-to-1 with an OS thread.
 *  - Creating an OS thread is expensive: ~1 MB stack, kernel scheduler entry.
 *  - A JVM can realistically support ~thousands of platform threads before
 *    memory and context-switching overhead causes degradation.
 *  - When a platform thread calls blocking I/O (Thread.sleep, socket read, DB query),
 *    the OS thread is BLOCKED and cannot be used for other work.
 *  - Fixed thread pools (e.g. pool of 20) limit concurrency: if all 20 threads
 *    are sleeping waiting for I/O, the 21st request must WAIT in the queue.
 *  - This is the root cause of the "thread-per-request" scalability ceiling.
 *
 *  VIRTUAL THREADS (Project Loom — JEP 444, GA in Java 21):
 *  ----------------------------------------------------------
 *  - Virtual threads are lightweight, user-space threads managed by the JVM.
 *  - They are NOT mapped 1-to-1 with OS threads. Many virtual threads share
 *    a small pool of "carrier" (OS) threads (typically = number of CPU cores).
 *  - Creating a virtual thread is cheap: ~few hundred bytes of initial stack.
 *    You can have MILLIONS of them simultaneously.
 *  - When a virtual thread hits a blocking call (Thread.sleep, socket I/O),
 *    the JVM UNMOUNTS it from the carrier thread, freeing that carrier to run
 *    another virtual thread. When the I/O completes, the virtual thread is
 *    REMOUNTED on any available carrier thread and resumes.
 *  - This makes virtual threads ideal for I/O-bound workloads: you get
 *    high concurrency with almost no overhead.
 *  - The KEY insight: the programming model is IDENTICAL to platform threads
 *    (blocking code, no callbacks, no reactive chains) — Loom makes blocking
 *    I/O cheap, not non-blocking.
 *
 *  WHAT THIS CLASS DOES:
 *  ----------------------
 *  Defines two ExecutorService beans:
 *    1. "classicExecutor" — traditional fixed ThreadPoolExecutor (Phase 1)
 *    2. "loomExecutor"    — one virtual thread per task (Phase 2)
 *
 *  The AggregatorService receives whichever executor is injected — the
 *  business logic is EXACTLY the same for both. Only the executor differs.
 */
@Configuration
public class ExecutorConfig {

    /**
     * PHASE 1 — Classic fixed-size ThreadPoolExecutor.
     *
     * Key characteristics:
     *   - Fixed pool of N platform (OS) threads.
     *   - Bounded work queue: excess tasks wait until a thread is free.
     *   - Thread names are "classic-pool-N" — visible in DataSourceResult.threadName.
     *   - Under load with sources > pool size, total latency grows because tasks queue.
     *
     * The @Qualifier("classicExecutor") annotation is required because there are
     * two ExecutorService beans — Spring needs to know which one to inject.
     */
    @Bean("classicExecutor")
    public ExecutorService classicExecutor(AppConfig config) {
        AppConfig.ThreadPoolConfig tpConfig = config.getThreadPool();

        // Named thread factory so we can clearly see "classic-pool-N" in the response.
        // AtomicLong ensures thread-safe sequential naming without locking.
        AtomicLong threadIndex = new AtomicLong(0);
        ThreadFactory namedFactory = runnable -> {
            Thread t = new Thread(runnable);
            t.setName("classic-pool-" + threadIndex.getAndIncrement());
            // Platform threads are non-daemon by default — they prevent JVM exit.
            // For a server app this is correct; background worker threads should be daemon.
            t.setDaemon(false);
            return t;
        };

        return new ThreadPoolExecutor(
                tpConfig.getCoreSize(),                          // core threads always alive
                tpConfig.getMaxSize(),                           // max threads (same = fixed pool)
                60L, TimeUnit.SECONDS,                           // keep-alive for threads above core
                new LinkedBlockingQueue<>(tpConfig.getQueueCapacity()), // bounded work queue
                namedFactory
                // Default rejection policy: AbortPolicy — throws RejectedExecutionException
                // when both threads and queue are exhausted. Consider CallerRunsPolicy for
                // graceful back-pressure in production.
        );
    }

    /**
     * PHASE 2 — Project Loom: one virtual thread per task.
     *
     * Key characteristics:
     *   - A brand-new virtual thread is created for every submitted task.
     *   - Virtual threads are cheap enough (few hundred bytes) that this is fine.
     *   - No fixed pool size limit — concurrency scales to match workload.
     *   - Thread names are "virtual-thread-N [virtual]" — visible in DataSourceResult.threadName.
     *   - Under the same load, totalDurationMs stays near max(individual delays) regardless
     *     of how many sources are requested, because nothing ever queues.
     *
     * Executors.newVirtualThreadPerTaskExecutor() is the canonical Loom API introduced
     * in Java 21 (JEP 444). Internally it uses Thread.ofVirtual().factory().
     *
     * We add a custom name prefix via Thread.ofVirtual().name(...) so the thread names
     * are descriptive in the response output (default would be "" or a bare number).
     *
     * WHY we inject VirtualThreadTracker here:
     * Thread.getAllStackTraces() and ThreadMXBean both exclude virtual threads in Java 21
     * (intentionally — there can be millions of them). The only portable way to count
     * live virtual threads is to instrument the task submission point ourselves.
     * We wrap every Runnable with tracker.track(r) so onStart/onComplete bracket each task.
     */
    @Bean("loomExecutor")
    public ExecutorService loomExecutor(VirtualThreadTracker tracker) {
        // Thread.ofVirtual() is the Java 21 builder API for virtual threads.
        // .name("prefix", startIndex) creates sequential names: "virtual-thread-0", "virtual-thread-1", ...
        ThreadFactory virtualFactory = Thread.ofVirtual()
                .name("virtual-thread-", 0)
                .factory();

        // Wrap the raw factory: each submitted Runnable is bracketed with
        // tracker.onStart() / tracker.onComplete() so the snapshot endpoint
        // can report accurate live virtual thread counts.
        ThreadFactory trackingFactory = runnable ->
                virtualFactory.newThread(tracker.track(runnable));

        // newThreadPerTaskExecutor: creates exactly one new thread (virtual, in our case)
        // per submitted task. No pooling — virtual threads are too cheap to pool.
        return Executors.newThreadPerTaskExecutor(trackingFactory);
    }
}
