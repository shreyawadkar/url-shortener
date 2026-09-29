package com.shortener.events;

/** Used when Kafka is not configured: events go straight to the aggregator in-process. */
public class InMemoryClickEventPublisher implements ClickEventPublisher {

    private final ClickAggregator aggregator;

    public InMemoryClickEventPublisher(ClickAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public void publish(ClickEvent event) {
        aggregator.record(event.code());
    }

    @Override
    public String type() {
        return "in-memory";
    }
}
