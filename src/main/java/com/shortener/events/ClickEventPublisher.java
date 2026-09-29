package com.shortener.events;

public interface ClickEventPublisher {

    void publish(ClickEvent event);

    String type();
}
