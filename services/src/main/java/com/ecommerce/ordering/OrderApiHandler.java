package com.ecommerce.ordering;

import com.ecommerce.shared.*;
import com.amazonaws.services.lambda.runtime.events.*;
import java.util.Map;

public final class OrderApiHandler extends HttpHandler {
    private final ItemStore store;
    public OrderApiHandler() { this(DynamoStore.fromEnvironment()); }
    public OrderApiHandler(ItemStore store) { this.store = store; }
    protected APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent event) {
        if (!"GET".equals(event.getHttpMethod())) return response(405, Map.of("message", "Unsupported route"));
        String user = path(event, "userName");
        if (user == null) return response(200, store.scan());
        String date = event.getQueryStringParameters() == null ? null : event.getQueryStringParameters().get("orderDate");
        return date == null || date.isBlank() ? response(200, store.query("userName", user))
                : found(store.get(Map.of("userName", user, "orderDate", date)), "Order");
    }
}
