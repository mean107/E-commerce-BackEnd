package com.ecommerce.basket;

import com.ecommerce.shared.Json;
import java.util.Map;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;

public final class EventBridgePublisher implements CheckoutPublisher {
    private final EventBridgeClient client;
    private final String bus, source, detailType;
    public EventBridgePublisher(EventBridgeClient client, String bus, String source, String detailType) {
        this.client = client; this.bus = bus; this.source = source; this.detailType = detailType;
    }
    public static EventBridgePublisher fromEnvironment() {
        return new EventBridgePublisher(EventBridgeClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                System.getenv("EVENT_BUSNAME"), System.getenv("EVENT_SOURCE"), System.getenv("EVENT_DETAILTYPE"));
    }
    public void publish(Map<String, Object> detail) {
        var result = client.putEvents(PutEventsRequest.builder().entries(PutEventsRequestEntry.builder()
                .eventBusName(bus).source(source).detailType(detailType).detail(Json.write(detail)).build()).build());
        if (result.failedEntryCount() != null && result.failedEntryCount() > 0)
            throw new IllegalStateException("EventBridge rejected checkout event");
    }
}
