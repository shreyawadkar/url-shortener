package com.shortener;

import com.shortener.config.AppProperties;
import com.shortener.shard.MysqlUri;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MysqlUriTest {

    @Test
    void splitsHostedUriIntoOneDatabasePerShard() {
        List<AppProperties.Shard> shards = MysqlUri.toShards(
                "mysql://avnadmin:p%40ss%3Aword@mysql-abc.aivencloud.com:12345/defaultdb?ssl-mode=REQUIRED", 3);

        assertThat(shards).hasSize(3);
        assertThat(shards.get(2).url()).startsWith("jdbc:mysql://mysql-abc.aivencloud.com:12345/shard2?sslMode=REQUIRED");
        assertThat(shards.get(0).url()).contains("createDatabaseIfNotExist=true");
        assertThat(shards.get(0).username()).isEqualTo("avnadmin");
        assertThat(shards.get(0).password()).isEqualTo("p@ss:word");
    }

    @Test
    void rejectsNonMysqlUri() {
        assertThatThrownBy(() -> MysqlUri.toShards("postgres://u:p@h/db", 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
