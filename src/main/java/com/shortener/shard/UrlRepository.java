package com.shortener.shard;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UrlRepository {

    private static final RowMapper<UrlRecord> MAPPER = (rs, i) -> new UrlRecord(
            rs.getString("code"),
            rs.getString("long_url"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("expires_at")),
            rs.getLong("click_count"),
            toInstant(rs.getTimestamp("last_accessed_at")));

    private final ShardManager shards;

    public UrlRepository(ShardManager shards) {
        this.shards = shards;
    }

    /** Throws DuplicateKeyException if the code is taken; the unique index is the source of truth. */
    public void insert(String code, String longUrl, Instant expiresAt) {
        shards.forCode(code).jdbc().update(
                "INSERT INTO urls (code, long_url, created_at, expires_at) VALUES (?, ?, ?, ?)",
                code, longUrl, Timestamp.from(Instant.now()), expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    public Optional<UrlRecord> findByCode(String code) {
        List<UrlRecord> rows = shards.forCode(code).jdbc().query(
                "SELECT code, long_url, created_at, expires_at, click_count, last_accessed_at FROM urls WHERE code = ?",
                MAPPER, code);
        return rows.stream().findFirst();
    }

    /** Applies aggregated click deltas to a single shard in one JDBC batch. */
    public void addClicks(Shard shard, Map<String, Long> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        Timestamp now = Timestamp.from(Instant.now());
        List<Object[]> args = deltas.entrySet().stream()
                .map(e -> new Object[]{e.getValue(), now, e.getKey()})
                .toList();
        shard.jdbc().batchUpdate(
                "UPDATE urls SET click_count = click_count + ?, last_accessed_at = ? WHERE code = ?", args);
    }

    public long countRows(Shard shard) {
        Long n = shard.jdbc().queryForObject("SELECT COUNT(*) FROM urls", Long.class);
        return n == null ? 0 : n;
    }

    public long totalClicks(Shard shard) {
        Long n = shard.jdbc().queryForObject("SELECT COALESCE(SUM(click_count), 0) FROM urls", Long.class);
        return n == null ? 0 : n;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
