package com.shortener.shard;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One MySQL shard: its connection pool plus a bounded worker pool that acts as a bulkhead,
 * so a slow shard can only tie up its own threads and never starves the other shards.
 */
public final class Shard implements AutoCloseable {

    private final int index;
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final ThreadPoolExecutor workers;

    public Shard(int index, HikariDataSource dataSource, int workerThreads) {
        this.index = index;
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                workerThreads, workerThreads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000),
                r -> {
                    Thread t = new Thread(r, "shard-" + index + "-worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // Back-pressure: when the queue is full the submitting thread does the work itself.
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public int index() {
        return index;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public ThreadPoolExecutor workers() {
        return workers;
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
