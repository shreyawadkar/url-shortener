package com.shortener.web;

import com.shortener.service.UrlService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
public class RedirectController {

    private final UrlService service;

    public RedirectController(UrlService service) {
        this.service = service;
    }

    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<?> redirect(@PathVariable String code,
                                      @RequestHeader(value = HttpHeaders.REFERER, required = false) String referrer,
                                      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return service.resolve(code, referrer, userAgent)
                .<ResponseEntity<?>>map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .build())
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Short URL not found or expired", "code", code)));
    }
}
