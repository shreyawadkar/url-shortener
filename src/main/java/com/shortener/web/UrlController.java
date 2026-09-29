package com.shortener.web;

import com.shortener.cache.UrlCache;
import com.shortener.events.ClickEventPublisher;
import com.shortener.service.UrlService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class UrlController {

    public record CreateRequest(String url, String customAlias, Integer ttlDays) {
    }

    private final UrlService service;
    private final UrlCache cache;
    private final ClickEventPublisher publisher;

    public UrlController(UrlService service, UrlCache cache, ClickEventPublisher publisher) {
        this.service = service;
        this.cache = cache;
        this.publisher = publisher;
    }

    @PostMapping("/urls")
    public ResponseEntity<UrlService.Created> create(@RequestBody CreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req.url(), req.customAlias(), req.ttlDays()));
    }

    @PostMapping("/urls/batch")
    public ResponseEntity<List<Object>> createBatch(@RequestBody List<Map<String, Object>> items) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createBatch(items));
    }

    @GetMapping("/urls/{code}/stats")
    public ResponseEntity<UrlService.Stats> stats(@PathVariable String code) {
        return ResponseEntity.of(service.stats(code));
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cache", cache.type());
        m.put("events", publisher.type());
        m.put("shards", service.shardStats());
        return m;
    }
}
