package com.loom.benchmark.controller;

import com.loom.benchmark.model.MemorySnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private static final long DEFAULT_STACK_SIZE_BYTES = 512 * 1024; // 512 KB JVM default

    @GetMapping("/snapshot")
    public MemorySnapshot snapshot() {
        var memBean = ManagementFactory.getMemoryMXBean();
        var threadBean = ManagementFactory.getThreadMXBean();

        // Heap and non-heap totals
        MemorySnapshot.MemoryUsage heap    = MemorySnapshot.MemoryUsage.of(memBean.getHeapMemoryUsage());
        MemorySnapshot.MemoryUsage nonHeap = MemorySnapshot.MemoryUsage.of(memBean.getNonHeapMemoryUsage());

        // Per-pool breakdown
        List<MemorySnapshot.MemoryPoolInfo> pools = ManagementFactory.getMemoryPoolMXBeans()
                .stream()
                .map(pool -> new MemorySnapshot.MemoryPoolInfo(
                        pool.getName(),
                        pool.getType() == MemoryType.HEAP ? "HEAP" : "NON_HEAP",
                        MemorySnapshot.MemoryUsage.of(pool.getUsage())
                ))
                .toList();

        // GC stats
        List<MemorySnapshot.GcInfo> gc = ManagementFactory.getGarbageCollectorMXBeans()
                .stream()
                .map(g -> new MemorySnapshot.GcInfo(
                        g.getName(),
                        g.getCollectionCount(),
                        g.getCollectionTime()
                ))
                .toList();

        // Thread stack estimate
        int platformThreads = threadBean.getThreadCount();
        long totalStackBytes = platformThreads * DEFAULT_STACK_SIZE_BYTES;
        var threadMemory = new MemorySnapshot.ThreadMemoryEstimate(
                platformThreads,
                DEFAULT_STACK_SIZE_BYTES,
                totalStackBytes,
                "Estimate only: actual stack RSS depends on -Xss flag and stack depth. " +
                "Virtual threads use ~1 KB initial stack (freed when parked), not counted here."
        );

        return new MemorySnapshot(
                Instant.now().toString(),
                heap,
                nonHeap,
                pools,
                gc,
                threadMemory
        );
    }
}
