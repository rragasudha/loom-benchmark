package com.loom.benchmark.executor;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * Accurate counter for live virtual threads.
 *
 * WHY THIS EXISTS:
 * Thread.getAllStackTraces() intentionally excludes virtual threads in Java 21.
 * The JDK team made this choice deliberately: virtual threads can number in the
 * millions, so enumerating all of them on every call would be catastrophically
 * expensive. The fix for that API (returning virtual threads if explicitly asked)
 * is tracked but not yet shipped as of Java 21.
 *
 * ThreadMXBean.getThreadCount() also only counts platform threads.
 *
 * The only reliable way to know how many virtual threads are live right now is
 * to count them yourself. We do that here by wrapping each submitted Runnable:
 * increment on entry, decrement in a finally block on exit.
 *
 * The loomExecutor bean in ExecutorConfig injects this tracker and wraps every
 * task. The ThreadController reads these counters for the /api/threads/snapshot
 * response.
 *
 * All three counters are AtomicLong, so they are safe to read and write from
 * any number of concurrent virtual threads without locking.
 */
@Component
public class VirtualThreadTracker {

    /** Number of virtual threads currently executing (started but not yet finished). */
    private final AtomicLong active = new AtomicLong(0);

    /** Cumulative virtual threads started since the JVM launched this app. */
    private final AtomicLong startedEver = new AtomicLong(0);

    /** Cumulative virtual threads that have completed (normally or with exception). */
    private final AtomicLong completedEver = new AtomicLong(0);

    /** Called by the executor wrapper immediately before the task body runs. */
    public void onStart() {
        active.incrementAndGet();
        startedEver.incrementAndGet();
    }

    /** Called by the executor wrapper in a finally block after the task body exits. */
    public void onComplete() {
        active.decrementAndGet();
        completedEver.incrementAndGet();
    }

    public long getActive()        { return active.get(); }
    public long getStartedEver()   { return startedEver.get(); }
    public long getCompletedEver() { return completedEver.get(); }

    /**
     * Wraps a Runnable with tracking. The loomExecutor factory calls this so
     * every submitted task is bracketed by onStart/onComplete.
     */
    public Runnable track(Runnable task) {
        return () -> {
            onStart();
            try {
                task.run();
            } finally {
                onComplete();
            }
        };
    }
}
