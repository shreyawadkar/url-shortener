package com.shortener;

import com.shortener.shard.ShardRouter;
import com.shortener.util.Base62;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardRouterTest {

    @Test
    void routingIsDeterministic() {
        ShardRouter router = new ShardRouter(3);
        assertThat(router.shardFor("abc1234")).isEqualTo(router.shardFor("abc1234"));
    }

    @Test
    void randomCodesSpreadEvenlyAcrossThreeShards() {
        ShardRouter router = new ShardRouter(3);
        int[] counts = new int[3];
        int n = 30_000;
        for (int i = 0; i < n; i++) {
            counts[router.shardFor(Base62.randomCode(7))]++;
        }
        for (int c : counts) {
            assertThat(c).isBetween((int) (n / 3 * 0.95), (int) (n / 3 * 1.05));
        }
    }

    @Test
    void base62EncodesKnownValues() {
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(62)).isEqualTo("10");
        assertThat(Base62.randomCode(7)).hasSize(7).matches("[0-9A-Za-z]{7}");
    }
}
