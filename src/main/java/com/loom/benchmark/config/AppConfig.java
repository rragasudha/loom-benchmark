package com.loom.benchmark.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration bound to the "app" prefix in application.yml.
 *
 * Spring Boot's @ConfigurationProperties binding means every field here maps
 * directly to application.yml without needing @Value("${...}") scattered
 * across the codebase. It also enables IDE auto-completion for the YAML.
 *
 * Hierarchy:
 *   app:
 *     thread-pool:       → ThreadPoolConfig
 *       core-size: 20
 *       max-size: 20
 *       queue-capacity: 100
 *     simulation:        → SimulationConfig
 *       min-delay-ms: 200
 *       max-delay-ms: 800
 */
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private final ThreadPoolConfig threadPool = new ThreadPoolConfig();
    private final SimulationConfig simulation = new SimulationConfig();

    public ThreadPoolConfig getThreadPool() {
        return threadPool;
    }

    public SimulationConfig getSimulation() {
        return simulation;
    }

    // -------------------------------------------------------------------------
    // Nested config class for classic thread pool settings
    // -------------------------------------------------------------------------
    public static class ThreadPoolConfig {

        /**
         * Core thread count — threads kept alive even when idle.
         * Tuning: For I/O-bound work, you typically set this higher than CPU cores.
         * Rule of thumb: coreSize = N * (1 + wait_time / compute_time).
         * For pure I/O (wait >> compute), N=20 is a common starting point.
         */
        private int coreSize = 20;

        /**
         * Max thread count — pool grows up to this under burst load.
         * We keep coreSize == maxSize (fixed pool) to make the constraint explicit.
         */
        private int maxSize = 20;

        /**
         * Work queue capacity. When all threads are busy and queue is full,
         * new tasks are REJECTED. This simulates back-pressure — important to
         * understand under extreme load tests.
         */
        private int queueCapacity = 100;

        public int getCoreSize() { return coreSize; }
        public void setCoreSize(int coreSize) { this.coreSize = coreSize; }

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }

        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
    }

    // -------------------------------------------------------------------------
    // Nested config class for simulated I/O delay settings
    // -------------------------------------------------------------------------
    public static class SimulationConfig {

        /**
         * Minimum simulated I/O delay in milliseconds.
         * Represents the fastest possible response from an external service.
         */
        private int minDelayMs = 200;

        /**
         * Maximum simulated I/O delay in milliseconds.
         * Represents a slow/tail-latency response from an external service.
         */
        private int maxDelayMs = 800;

        public int getMinDelayMs() { return minDelayMs; }
        public void setMinDelayMs(int minDelayMs) { this.minDelayMs = minDelayMs; }

        public int getMaxDelayMs() { return maxDelayMs; }
        public void setMaxDelayMs(int maxDelayMs) { this.maxDelayMs = maxDelayMs; }
    }
}
