package com.shortener;

import com.shortener.events.ClickAggregator;
import com.shortener.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.clicks.flush-interval-ms=600000")
class UrlShortenerIntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    UrlService service;

    @Autowired
    ClickAggregator aggregator;

    @Test
    void createThenRedirect() {
        ResponseEntity<Map> created = http.postForEntity("/api/v1/urls",
                Map.of("url", "https://example.com/a/b?c=d"), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = (String) created.getBody().get("code");

        ResponseEntity<String> redirect = http.getForEntity("/" + code, String.class);
        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(redirect.getHeaders().getLocation()).hasToString("https://example.com/a/b?c=d");
    }

    @Test
    void unknownCodeIs404AndBadUrlIs400() {
        assertThat(http.getForEntity("/doesNotExist", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.postForEntity("/api/v1/urls", Map.of("url", "ftp://x"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void duplicateAliasIs409() {
        Map<String, String> body = Map.of("url", "https://example.com", "customAlias", "my-alias");
        assertThat(http.postForEntity("/api/v1/urls", body, Map.class).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(http.postForEntity("/api/v1/urls", body, Map.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void concurrentAliasClaimsHaveExactlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<HttpStatus>> tasks = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            tasks.add(() -> (HttpStatus) http.postForEntity("/api/v1/urls",
                    Map.of("url", "https://example.com/race", "customAlias", "race-alias"), Map.class).getStatusCode());
        }
        List<HttpStatus> results = new ArrayList<>();
        for (Future<HttpStatus> f : pool.invokeAll(tasks)) {
            results.add(f.get());
        }
        pool.shutdown();
        assertThat(results).filteredOn(s -> s == HttpStatus.CREATED).hasSize(1);
        assertThat(results).filteredOn(s -> s == HttpStatus.CONFLICT).hasSize(31);
    }

    @Test
    void concurrentClicksAreCountedExactly() throws Exception {
        String code = service.create("https://example.com/clicks", null, null).code();
        int threads = 8, perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            tasks.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    service.resolve(code, null, null);
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        aggregator.flush();
        assertThat(service.stats(code).orElseThrow().clicks()).isEqualTo((long) threads * perThread);
    }

    @Test
    void batchCreateSpreadsAcrossShards() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            items.add(Map.of("url", "https://example.com/batch/" + i));
        }
        List<Object> results = service.createBatch(items);
        Set<Integer> shardsUsed = new HashSet<>();
        for (Object r : results) {
            assertThat(r).isInstanceOf(UrlService.Created.class);
            shardsUsed.add(((UrlService.Created) r).shard());
        }
        assertThat(shardsUsed).containsExactlyInAnyOrder(0, 1, 2);
    }
}
