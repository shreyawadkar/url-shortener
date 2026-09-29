package com.shortener.shard;

import com.shortener.config.AppProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ShardManager {

    private static final Logger log = LoggerFactory.getLogger(ShardManager.class);

    private final List<Shard> shards;
    private final ShardRouter router;

    public ShardManager(AppProperties props) {
        List<AppProperties.Shard> configs = props.shards();
        if (configs == null || configs.isEmpty()) {
            throw new IllegalStateException("At least one shard must be configured under app.shards");
        }
        List<Shard> built = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            AppProperties.Shard cfg = configs.get(i);
            HikariConfig hc = new HikariConfig();
            hc.setPoolName("shard-" + i);
            hc.setJdbcUrl(cfg.url());
            hc.setUsername(cfg.username());
            hc.setPassword(cfg.password());
            hc.setMaximumPoolSize(props.shardPoolSize());
            hc.setMinimumIdle(Math.min(2, props.shardPoolSize()));
            hc.setConnectionTimeout(3_000);
            HikariDataSource ds = new HikariDataSource(hc);

            ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
            schema.execute(ds);

            built.add(new Shard(i, ds, 4));
            log.info("Shard {} ready ({})", i, redact(cfg.url()));
        }
        this.shards = List.copyOf(built);
        this.router = new ShardRouter(shards.size());
    }

    public Shard forCode(String code) {
        return shards.get(router.shardFor(code));
    }

    public Shard get(int index) {
        return shards.get(index);
    }

    public List<Shard> all() {
        return shards;
    }

    public ShardRouter router() {
        return router;
    }

    @PreDestroy
    public void close() {
        shards.forEach(Shard::close);
    }

    private static String redact(String jdbcUrl) {
        int q = jdbcUrl.indexOf('?');
        return q < 0 ? jdbcUrl : jdbcUrl.substring(0, q);
    }
}
