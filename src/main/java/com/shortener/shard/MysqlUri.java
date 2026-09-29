package com.shortener.shard;

import com.shortener.config.AppProperties;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns one hosted-MySQL URI (the {@code mysql://user:pass@host:port/db} form Aiven, PlanetScale
 * and others hand out) into N shard configs, one database per shard on that server.
 * The shard databases are created on first connect.
 */
public final class MysqlUri {

    private MysqlUri() {
    }

    public static List<AppProperties.Shard> toShards(String mysqlUri, int shardCount) {
        URI uri = URI.create(mysqlUri.trim());
        if (!"mysql".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("MYSQL_URL must look like mysql://user:password@host:port/db");
        }
        String userInfo = uri.getRawUserInfo();
        if (userInfo == null || !userInfo.contains(":")) {
            throw new IllegalArgumentException("MYSQL_URL must include user:password");
        }
        int colon = userInfo.indexOf(':');
        String user = URLDecoder.decode(userInfo.substring(0, colon), StandardCharsets.UTF_8);
        String password = URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8);
        int port = uri.getPort() > 0 ? uri.getPort() : 3306;

        List<AppProperties.Shard> shards = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            String jdbc = "jdbc:mysql://" + uri.getHost() + ":" + port + "/shard" + i
                    + "?sslMode=" + (isLocal(uri.getHost()) ? "PREFERRED" : "REQUIRED")
                    + "&createDatabaseIfNotExist=true&rewriteBatchedStatements=true";
            shards.add(new AppProperties.Shard(jdbc, user, password));
        }
        return shards;
    }

    private static boolean isLocal(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1");
    }
}
