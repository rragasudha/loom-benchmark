package com.loom.benchmark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.loom.benchmark.config.AppConfig;

/**
 * Entry point for the Loom Benchmark application.
 *
 * This application is a learning demo that compares:
 *   - Phase 1 (CLASSIC): Traditional fixed-size ThreadPoolExecutor
 *   - Phase 2 (LOOM):    Project Loom virtual threads via Executors.newVirtualThreadPerTaskExecutor()
 *   - Phase 3 (BONUS):   Spring Boot's global spring.threads.virtual.enabled=true switch
 *
 * The workload is I/O-bound (simulated with Thread.sleep), which is exactly where
 * virtual threads shine: they are "parked" during blocking calls without consuming
 * an OS thread, so you can have millions of them vs. ~thousands of platform threads.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppConfig.class)
public class LoomBenchmarkApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomBenchmarkApplication.class, args);
    }
}
