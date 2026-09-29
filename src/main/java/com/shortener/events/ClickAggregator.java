package com.shortener.events;

import com.shortener.config.AppProperties;
import com.shortener.shard.Shard;
import com.shortener.shard.ShardManager;
import com.shortener.shard.UrlRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Buffers click counts in memory and writes them to the shards in periodic batches.
 *
 * <p>Concurrency model: any number of request/consumer threads call {@link #record}; each
 * uses {@link ConcurrentHashMap#merge}, which is atomic per key. The flusher drains keys with
 * {@link ConcurrentHashMap#remove}, also atomic, so a click is counted either in the drained
 * batch or in the next one — never lost, never double-counted. Each shard's batch runs on
 * that shard's own worker pool, so the three shards are written in parallel and a slow
 * shard only delays itself.
 */
@Component
public class ClickAggregator {

    private static final Logger log = LoggerFactory.getLogger(ClickAggregator.class);

    private final ConcurrentHashMap<String, Long> pending = new ConcurrentHashMap<>();
    private final ShardManager shards;
    private final UrlRepository repo;
    private final ScheduledExecutorService scheduler;
    private final Counter recorded;
    private final Counter flushed;

    public ClickAggregator(ShardManager shards, UrlRepository repo, AppProperties props, MeterRegistry meters) {
        this.shards = shards;
        this.repo = repo;
        this.recorded = meters.counter("shortener.clicks.recorded");
        this.flushed = meters.counter("shortener.clicks.flushed");
        meters.gauge("shortener.clicks.pending.codes", pending, Map::size);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "click-flusher");
            t.setDaemon(true);
            return t;
        });
        long interval = props.clicks().flushIntervalMs();
        scheduler.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void record(String code) {
        pending.merge(code, 1L, Long::sum);
        recorded.increment();
    }

    /** Clicks recorded but not yet written to the shard. */
    public long pendingFor(String code) {
        return pending.getOrDefault(code, 0L);
    }

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("Click flush failed", e);
        }
    }

    /** Drains the buffer and writes each shard's share in parallel. Returns clicks written. */
    public long flush() {
        if (pending.isEmpty()) {
            return 0;
        }
        List<Map<String, Long>> byShard = new ArrayList<>();
        for (int i = 0; i < shards.all().size(); i++) {
            byShard.add(new HashMap<>());
        }
        for (String code : pending.keySet()) {
            Long n = pending.remove(code);
            if (n != null) {
                byShard.get(shards.router().shardFor(code)).put(code, n);
            }
        }

        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < byShard.size(); i++) {
            Map<String, Long> batch = byShard.get(i);
            if (batch.isEmpty()) {
                continue;
            }
            Shard shard = shards.get(i);
            futures.add(shard.workers().submit(() -> writeBatch(shard, batch)));
        }
        long total = 0;
        for (Future<Long> f : futures) {
            try {
                total += f.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Shard click batch failed", e);
            }
        }
        flushed.increment(total);
        return total;
    }

    private long writeBatch(Shard shard, Map<String, Long> batch) {
        try {
            repo.addClicks(shard, batch);
            return batch.values().stream().mapToLong(Long::longValue).sum();
        } catch (RuntimeException e) {
            // Put the counts back so the next flush retries them.
            batch.forEach((code, n) -> pending.merge(code, n, Long::sum));
            log.warn("Shard {} click batch of {} codes failed, will retry: {}",
                    shard.index(), batch.size(), e.getMessage());
            return 0;
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        flushSafely();
    }
}
