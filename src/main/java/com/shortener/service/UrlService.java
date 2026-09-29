package com.shortener.service;

import com.shortener.cache.UrlCache;
import com.shortener.config.AppProperties;
import com.shortener.events.ClickAggregator;
import com.shortener.events.ClickEvent;
import com.shortener.events.ClickEventPublisher;
import com.shortener.shard.Shard;
import com.shortener.shard.ShardManager;
import com.shortener.shard.UrlRecord;
import com.shortener.shard.UrlRepository;
import com.shortener.util.Base62;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Service
public class UrlService {

    public static final int MAX_BATCH = 100;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9_-]{3,32}");
    private static final Set<String> RESERVED = Set.of("api", "actuator", "health", "static", "favicon", "admin");

    public record Created(String code, String shortUrl, String longUrl, Instant expiresAt, int shard) {
    }

    public record Stats(String code, String longUrl, Instant createdAt, Instant expiresAt,
                        long clicks, Instant lastAccessedAt, int shard) {
    }

    public record ShardStats(int shard, long urls, long clicks, int activeConnections, int idleConnections,
                             int workerQueue) {
    }

    private final UrlRepository repo;
    private final UrlCache cache;
    private final ClickEventPublisher clicks;
    private final ClickAggregator aggregator;
    private final ShardManager shards;
    private final AppProperties props;
    private final Timer redirectTimer;
    private final Timer createTimer;
    private final Counter cacheHits;
    private final Counter cacheMisses;

    public UrlService(UrlRepository repo, UrlCache cache, ClickEventPublisher clicks, ClickAggregator aggregator,
                      ShardManager shards, AppProperties props, MeterRegistry meters) {
        this.repo = repo;
        this.cache = cache;
        this.clicks = clicks;
        this.aggregator = aggregator;
        this.shards = shards;
        this.props = props;
        this.redirectTimer = meters.timer("shortener.redirect");
        this.createTimer = meters.timer("shortener.create");
        this.cacheHits = meters.counter("shortener.cache", "result", "hit");
        this.cacheMisses = meters.counter("shortener.cache", "result", "miss");
    }

    public Created create(String longUrl, String alias, Integer ttlDays) {
        return createTimer.record(() -> doCreate(longUrl, alias, ttlDays, null));
    }

    private Created doCreate(String longUrl, String alias, Integer ttlDays, String firstCandidate) {
        String url = validateUrl(longUrl);
        Instant expiresAt = null;
        if (ttlDays != null) {
            if (ttlDays < 1 || ttlDays > 3650) {
                throw new InvalidRequestException("ttlDays must be between 1 and 3650");
            }
            expiresAt = Instant.now().plus(Duration.ofDays(ttlDays));
        }

        String code;
        if (alias != null && !alias.isBlank()) {
            code = alias.trim();
            if (!ALIAS.matcher(code).matches()) {
                throw new InvalidRequestException("Alias must be 3-32 characters: letters, digits, '_' or '-'");
            }
            if (RESERVED.contains(code.toLowerCase())) {
                throw new InvalidRequestException("Alias '" + code + "' is reserved");
            }
            try {
                repo.insert(code, url, expiresAt);
            } catch (DuplicateKeyException e) {
                throw new AliasTakenException(code);
            }
        } else {
            code = insertWithRandomCode(url, expiresAt, firstCandidate);
        }

        cache.put(code, url, cacheTtl(expiresAt));
        return new Created(code, shortUrl(code), url, expiresAt, shards.router().shardFor(code));
    }

    /** The unique index arbitrates concurrent writers; on the rare collision we just draw again. */
    private String insertWithRandomCode(String url, Instant expiresAt, String firstCandidate) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = attempt == 0 && firstCandidate != null ? firstCandidate : Base62.randomCode(props.codeLength());
            try {
                repo.insert(code, url, expiresAt);
                return code;
            } catch (DuplicateKeyException ignored) {
                // collision, retry with a new code
            }
        }
        throw new IllegalStateException("Could not allocate a unique code after " + MAX_CODE_ATTEMPTS + " attempts");
    }

    /**
     * Creates many URLs at once. Each item runs on the worker pool of the shard its code maps to,
     * so inserts for different shards proceed in parallel while each shard's concurrency stays bounded.
     */
    public List<Object> createBatch(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new InvalidRequestException("Batch must contain at least one item");
        }
        if (items.size() > MAX_BATCH) {
            throw new InvalidRequestException("Batch size must be at most " + MAX_BATCH);
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String url = item.get("url") instanceof String s ? s : null;
            String alias = item.get("customAlias") instanceof String s ? s : null;
            Integer ttl = item.get("ttlDays") instanceof Number n ? n.intValue() : null;
            // Choose the code before dispatch so the item runs on the worker pool of the shard it will be written to.
            boolean hasAlias = alias != null && !alias.isBlank();
            String candidate = hasAlias ? null : Base62.randomCode(props.codeLength());
            Shard shard = shards.forCode(hasAlias ? alias.trim() : candidate);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return (Object) createTimer.record(() -> doCreate(url, alias, ttl, candidate));
                } catch (RuntimeException e) {
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("url", url);
                    err.put("error", e.getMessage());
                    return err;
                }
            }, shard.workers()));
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    /** Resolves a code to its target URL and records the click asynchronously. */
    public Optional<String> resolve(String code, String referrer, String userAgent) {
        return redirectTimer.record(() -> {
            Optional<String> target = lookup(code);
            target.ifPresent(u -> clicks.publish(
                    new ClickEvent(code, System.currentTimeMillis(), referrer, userAgent)));
            return target;
        });
    }

    private Optional<String> lookup(String code) {
        Optional<String> cached = cache.get(code);
        if (cached.isPresent()) {
            cacheHits.increment();
            return UrlCache.NOT_FOUND.equals(cached.get()) ? Optional.empty() : cached;
        }
        cacheMisses.increment();
        Optional<UrlRecord> rec = repo.findByCode(code);
        if (rec.isEmpty() || rec.get().isExpired(Instant.now())) {
            cache.put(code, UrlCache.NOT_FOUND, Duration.ofSeconds(props.cache().negativeTtlSeconds()));
            return Optional.empty();
        }
        cache.put(code, rec.get().longUrl(), cacheTtl(rec.get().expiresAt()));
        return Optional.of(rec.get().longUrl());
    }

    public Optional<Stats> stats(String code) {
        return repo.findByCode(code).map(r -> new Stats(
                r.code(), r.longUrl(), r.createdAt(), r.expiresAt(),
                r.clickCount() + aggregator.pendingFor(code), r.lastAccessedAt(),
                shards.router().shardFor(code)));
    }

    public List<ShardStats> shardStats() {
        return shards.all().stream().map(s -> {
            var pool = s.dataSource().getHikariPoolMXBean();
            return new ShardStats(s.index(), repo.countRows(s), repo.totalClicks(s),
                    pool == null ? 0 : pool.getActiveConnections(),
                    pool == null ? 0 : pool.getIdleConnections(),
                    s.workers().getQueue().size());
        }).toList();
    }

    public String shortUrl(String code) {
        String base = props.baseUrl();
        return (base.endsWith("/") ? base : base + "/") + code;
    }

    private Duration cacheTtl(Instant expiresAt) {
        Duration ttl = Duration.ofSeconds(props.cache().ttlSeconds());
        if (expiresAt != null) {
            Duration untilExpiry = Duration.between(Instant.now(), expiresAt);
            if (untilExpiry.compareTo(ttl) < 0) {
                ttl = untilExpiry.isNegative() || untilExpiry.isZero() ? Duration.ofSeconds(1) : untilExpiry;
            }
        }
        return ttl;
    }

    private static String validateUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException("url is required");
        }
        String url = raw.trim();
        if (url.length() > MAX_URL_LENGTH) {
            throw new InvalidRequestException("url must be at most " + MAX_URL_LENGTH + " characters");
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                throw new InvalidRequestException("url must be an absolute http(s) URL");
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("url is not a valid URL");
        }
        return url;
    }
}
