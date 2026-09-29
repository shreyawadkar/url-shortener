package com.shortener.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KafkaEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * AWS Lambda consumer for the click-events topic (MSK event source mapping).
 *
 * <p>Each invocation receives a batch of records, aggregates clicks per short code, routes each
 * code to its shard with the same CRC32 rule the API uses, and applies one JDBC batch per shard.
 * Connections are cached across warm invocations.
 *
 * <p>Env: SHARD0_URL, SHARD1_URL, SHARD2_URL, DB_USER, DB_PASSWORD.
 */
public class ClickEventHandler implements RequestHandler<KafkaEvent, String> {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SHARD_URLS = shardUrls();
    private static final Connection[] CONNECTIONS = new Connection[SHARD_URLS.size()];

    @Override
    public String handleRequest(KafkaEvent event, Context context) {
        List<Map<String, Long>> byShard = new ArrayList<>();
        for (int i = 0; i < SHARD_URLS.size(); i++) {
            byShard.add(new HashMap<>());
        }
        int records = 0;
        for (List<KafkaEvent.KafkaEventRecord> partition : event.getRecords().values()) {
            for (KafkaEvent.KafkaEventRecord rec : partition) {
                records++;
                try {
                    String value = new String(Base64.getDecoder().decode(rec.getValue()), StandardCharsets.UTF_8);
                    JsonNode node = JSON.readTree(value);
                    String code = node.path("code").asText(null);
                    if (code != null) {
                        byShard.get(shardFor(code)).merge(code, 1L, Long::sum);
                    }
                } catch (Exception e) {
                    context.getLogger().log("Skipping malformed record at offset " + rec.getOffset() + ": " + e.getMessage());
                }
            }
        }

        long written = 0;
        for (int i = 0; i < byShard.size(); i++) {
            if (!byShard.get(i).isEmpty()) {
                written += applyBatch(i, byShard.get(i));
            }
        }
        String summary = "records=" + records + " clicksWritten=" + written;
        context.getLogger().log(summary);
        return summary;
    }

    private static long applyBatch(int shard, Map<String, Long> deltas) {
        try {
            Connection conn = connection(shard);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE urls SET click_count = click_count + ?, last_accessed_at = ? WHERE code = ?")) {
                Timestamp now = Timestamp.from(Instant.now());
                long total = 0;
                for (Map.Entry<String, Long> e : deltas.entrySet()) {
                    ps.setLong(1, e.getValue());
                    ps.setTimestamp(2, now);
                    ps.setString(3, e.getKey());
                    ps.addBatch();
                    total += e.getValue();
                }
                ps.executeBatch();
                return total;
            }
        } catch (SQLException e) {
            // Throwing makes Lambda retry the batch instead of silently dropping clicks.
            CONNECTIONS[shard] = null;
            throw new RuntimeException("Shard " + shard + " update failed", e);
        }
    }

    private static synchronized Connection connection(int shard) throws SQLException {
        Connection c = CONNECTIONS[shard];
        if (c == null || !c.isValid(2)) {
            c = DriverManager.getConnection(SHARD_URLS.get(shard), System.getenv("DB_USER"), System.getenv("DB_PASSWORD"));
            CONNECTIONS[shard] = c;
        }
        return c;
    }

    static int shardFor(String code) {
        CRC32 crc = new CRC32();
        crc.update(code.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % SHARD_URLS.size());
    }

    private static List<String> shardUrls() {
        List<String> urls = new ArrayList<>();
        for (int i = 0; ; i++) {
            String url = System.getenv("SHARD" + i + "_URL");
            if (url == null || url.isBlank()) {
                break;
            }
            urls.add(url);
        }
        if (urls.isEmpty()) {
            urls.add("jdbc:mysql://localhost:3306/shortener");
        }
        return urls;
    }
}
