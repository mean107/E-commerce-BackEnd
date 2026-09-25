package com.ecommerce.product;

import com.ecommerce.shared.*;
import com.amazonaws.services.lambda.runtime.events.*;
import java.util.*;

public final class ProductHandler extends HttpHandler {
    private final ItemStore store;
    public ProductHandler() { this(DynamoStore.fromEnvironment()); }
    public ProductHandler(ItemStore store) { this.store = store; }

    protected APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent event) {
        String id = path(event, "id");
        switch (Objects.toString(event.getHttpMethod(), "")) {
            case "GET": return id == null ? response(200, store.scan()) : found(store.get(Map.of("id", id)), "Product");
            case "POST": {
                var item = body(event);
                Object supplied = item.get("id");
                if (supplied == null || "".equals(supplied)) supplied = UUID.randomUUID().toString();
                if (!(supplied instanceof String s) || s.isBlank()) throw new IllegalArgumentException("id must be a string");
                item.put("id", supplied);
                store.put(item);
                return response(201, item);
            }
            case "PUT": {
                if (id == null) break;
                var item = body(event);
                item.put("id", id);
                store.put(item);
                return response(200, item);
            }
            case "DELETE": {
                if (id == null) break;
                store.delete(Map.of("id", id));
                return response(204, null);
            }
        }
        return response(405, Map.of("message", "Unsupported route"));
    }
}
