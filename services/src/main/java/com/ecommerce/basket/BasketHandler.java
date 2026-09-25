package com.ecommerce.basket;

import com.ecommerce.shared.*;
import com.amazonaws.services.lambda.runtime.events.*;
import java.util.*;

public final class BasketHandler extends HttpHandler {
    private final ItemStore store;
    private final CheckoutPublisher publisher;
    public BasketHandler() { this(DynamoStore.fromEnvironment(), EventBridgePublisher.fromEnvironment()); }
    public BasketHandler(ItemStore store, CheckoutPublisher publisher) { this.store = store; this.publisher = publisher; }

    protected APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent event) {
        String user = path(event, "userName");
        String resource = Objects.toString(event.getResource(), Objects.toString(event.getPath(), ""));
        switch (Objects.toString(event.getHttpMethod(), "")) {
            case "GET": return user == null ? response(200, store.scan()) : found(store.get(Map.of("userName", user)), "Basket");
            case "POST": {
                var item = body(event);
                String name = userName(item);
                item.put("userName", name);
                if (resource.endsWith("/checkout")) {
                    var basket = store.get(Map.of("userName", name));
                    if (basket != null) item.put("basket", basket);
                    else item.remove("basket");
                    publisher.publish(item);
                    return response(202, Map.of("message", "Checkout event published", "detail", item));
                }
                store.put(item);
                return response(201, item);
            }
            case "DELETE": {
                if (user == null) break;
                store.delete(Map.of("userName", user));
                return response(204, null);
            }
        }
        return response(405, Map.of("message", "Unsupported route"));
    }
}
