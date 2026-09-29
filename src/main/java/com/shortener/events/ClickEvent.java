package com.shortener.events;

public record ClickEvent(String code, long timestampMs, String referrer, String userAgent) {
}
