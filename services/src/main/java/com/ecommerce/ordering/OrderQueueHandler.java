package com.ecommerce.ordering;

import com.ecommerce.shared.*;
import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.time.Clock;

/** Throws on failure: Lambda must not acknowledge a failed SQS batch. */
public final class OrderQueueHandler implements RequestHandler<SQSEvent, Void> {
    private final ItemStore store;
    private final Clock clock;
    public OrderQueueHandler() { this(DynamoStore.fromEnvironment(), Clock.systemUTC()); }
    public OrderQueueHandler(ItemStore store, Clock clock) { this.store = store; this.clock = clock; }

    public Void handleRequest(SQSEvent event, Context context) {
        for (var record : event.getRecords()) {
            var envelope = Json.object(record.getBody());
            Object payload = envelope.containsKey("detail") ? envelope.get("detail")
                    : envelope.getOrDefault("Detail", envelope);
            var order = payload instanceof String text ? Json.object(text) : Json.object(Json.write(payload));
            order.put("userName", HttpHandler.userName(order));
            Object date = order.get("orderDate");
            if (date == null || "".equals(date)) order.put("orderDate", clock.instant().toString());
            else if (!(date instanceof String s) || s.isBlank()) throw new IllegalArgumentException("orderDate must be a string");
            store.put(order);
        }
        return null;
    }
}
