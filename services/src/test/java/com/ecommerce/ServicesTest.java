package com.ecommerce;

import com.ecommerce.shared.*;
import com.ecommerce.product.ProductHandler;
import com.ecommerce.basket.*;
import com.ecommerce.ordering.*;
import com.amazonaws.services.lambda.runtime.events.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import static org.junit.jupiter.api.Assertions.*;

class ServicesTest {
    static class MemoryStore implements ItemStore {
        final Map<Map<String, Object>, Map<String, Object>> rows = new HashMap<>();
        final String[] keys;
        MemoryStore(String... keys) { this.keys = keys; }
        public Map<String, Object> get(Map<String, Object> key) { return rows.get(key); }
        public void put(Map<String, Object> item) {
            Map<String, Object> key = new HashMap<>();
            for (String k : keys) key.put(k, item.get(k));
            rows.put(key, new LinkedHashMap<>(item));
        }
        public void delete(Map<String, Object> key) { rows.remove(key); }
        public List<Map<String, Object>> scan() { return new ArrayList<>(rows.values()); }
        public List<Map<String, Object>> query(String k, String v) {
            return rows.values().stream().filter(i -> v.equals(i.get(k))).toList();
        }
    }
    static APIGatewayProxyRequestEvent request(String method, String path, String body) {
        return new APIGatewayProxyRequestEvent().withHttpMethod(method).withResource(path).withBody(body);
    }

    @Test void productCrudAndReplacement() {
        var db = new MemoryStore("id");
        var handler = new ProductHandler(db);
        var created = handler.handleRequest(request("POST", "/product", "{\"name\":\"Keyboard\",\"price\":123}"), null);
        assertEquals(201, created.getStatusCode());
        var item = Json.object(created.getBody());
        String id = (String) item.get("id");
        assertDoesNotThrow(() -> UUID.fromString(id));
        var one = request("GET", "/product/{id}", null).withPathParameters(Map.of("id", id));
        assertEquals(200, handler.handleRequest(one, null).getStatusCode());
        var update = request("PUT", "/product/{id}", "{\"name\":\"New\"}").withPathParameters(Map.of("id", id));
        handler.handleRequest(update, null);
        assertFalse(db.get(Map.of("id", id)).containsKey("price"));
        assertEquals(200, handler.handleRequest(request("GET", "/product", null), null).getStatusCode());
        assertEquals(204, handler.handleRequest(one.withHttpMethod("DELETE"), null).getStatusCode());
        assertEquals(404, handler.handleRequest(one.withHttpMethod("GET"), null).getStatusCode());
    }

    @Test void malformedJsonAndInvalidKeyReturn400() {
        var h = new ProductHandler(new MemoryStore("id"));
        assertEquals(400, h.handleRequest(request("POST", "/product", "{bad"), null).getStatusCode());
        assertEquals(400, h.handleRequest(request("POST", "/product", "{\"id\":123}"), null).getStatusCode());
        assertEquals(405, h.handleRequest(request("PATCH", "/product", "{}"), null).getStatusCode());
        assertEquals(400, h.handleRequest(request("POST", "/product", "null"), null).getStatusCode());
    }

    @Test void base64BodyIsDecoded() {
        var h = new ProductHandler(new MemoryStore("id"));
        String encoded = Base64.getEncoder().encodeToString("{\"name\":\"A\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(201, h.handleRequest(request("POST", "/product", encoded).withIsBase64Encoded(true), null).getStatusCode());
    }

    @Test void checkoutThroughQueueToOrderApi() {
        var basketStore = new MemoryStore("userName");
        var orderStore = new MemoryStore("userName", "orderDate");
        List<Map<String, Object>> events = new ArrayList<>();
        var basket = new BasketHandler(basketStore, events::add);
        assertEquals(400, basket.handleRequest(request("POST", "/basket", "{}"), null).getStatusCode());
        assertEquals(201, basket.handleRequest(request("POST", "/basket",
                "{\"username\":\"alice\",\"items\":[{\"productId\":\"p1\",\"quantity\":1}]}"), null).getStatusCode());
        var checkout = basket.handleRequest(request("POST", "/basket/checkout", "{\"userName\":\"alice\",\"totalPrice\":100}"), null);
        assertEquals(202, checkout.getStatusCode());
        assertTrue(events.getFirst().containsKey("basket"));
        assertNotNull(basketStore.get(Map.of("userName", "alice")));
        var queue = new OrderQueueHandler(orderStore, Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC));
        queue.handleRequest(sqs(Json.write(Map.of("detail", events.getFirst()))), null);
        var api = new OrderApiHandler(orderStore);
        var req = request("GET", "/order/{userName}", null).withPathParameters(Map.of("userName", "alice"));
        var result = api.handleRequest(req.withQueryStringParameters(Map.of("orderDate", "2026-09-14T00:00:00Z")), null);
        assertEquals(200, result.getStatusCode());
        assertEquals("alice", Json.object(result.getBody()).get("userName"));
        assertEquals(200, api.handleRequest(req.withQueryStringParameters(null), null).getStatusCode());
        assertEquals(404, api.handleRequest(req.withQueryStringParameters(Map.of("orderDate", "missing")), null).getStatusCode());
    }

    @Test void basketReadDeleteMissingCheckoutAndPublisherFailure() {
        var store = new MemoryStore("userName");
        List<Map<String, Object>> published = new ArrayList<>();
        var h = new BasketHandler(store, published::add);
        assertEquals(202, h.handleRequest(request("POST", "/basket/checkout", "{\"userName\":\"missing\"}"), null).getStatusCode());
        assertFalse(published.getFirst().containsKey("basket"));
        var req = request("GET", "/basket/{userName}", null).withPathParameters(Map.of("userName", "missing"));
        assertEquals(404, h.handleRequest(req, null).getStatusCode());
        assertEquals(204, h.handleRequest(req.withHttpMethod("DELETE"), null).getStatusCode());
        var failing = new BasketHandler(store, d -> { throw new IllegalStateException("private failure"); });
        var response = failing.handleRequest(request("POST", "/basket/checkout", "{\"userName\":\"a\"}"), null);
        assertEquals(500, response.getStatusCode());
        assertFalse(response.getBody().contains("private failure"));
    }

    @Test void sqsFailuresPropagateForRetry() {
        var failing = new MemoryStore("userName", "orderDate") {
            @Override public void put(Map<String, Object> item) { throw new IllegalStateException("DynamoDB unavailable"); }
        };
        var handler = new OrderQueueHandler(failing, Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> handler.handleRequest(sqs("{\"detail\":{\"userName\":\"alice\"}}"), null));
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(sqs("{\"detail\":{}}"), null));
        assertThrows(IllegalArgumentException.class, () -> handler.handleRequest(sqs("broken"), null));
    }

    @Test void legacyEventAndExplicitDateArePreserved() {
        var db = new MemoryStore("userName", "orderDate");
        var h = new OrderQueueHandler(db, Clock.systemUTC());
        h.handleRequest(sqs(Json.write(Map.of("Detail", "{\"username\":\"alice\",\"orderDate\":\"2026-01-01T00:00:00Z\"}"))), null);
        assertNotNull(db.get(Map.of("userName", "alice", "orderDate", "2026-01-01T00:00:00Z")));
    }

    @Test void nestedDynamoRoundTripPreservesNumbersAndNull() {
        var item = Json.object("{\"price\":123.45,\"items\":[{\"quantity\":2}],\"active\":true,\"empty\":null,\"map\":{},\"list\":[]}");
        var decoded = DynamoStore.decode(DynamoStore.encode(item));
        assertEquals(new BigDecimal("123.45"), decoded.get("price"));
        assertEquals(Json.write(item), Json.write(decoded));
    }

    @Test void eventBridgePartialFailureThrows() {
        var client = (EventBridgeClient) Proxy.newProxyInstance(EventBridgeClient.class.getClassLoader(),
                new Class<?>[]{EventBridgeClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("putEvents")) return PutEventsResponse.builder().failedEntryCount(1).build();
                    throw new UnsupportedOperationException(method.getName());
                });
        var publisher = new EventBridgePublisher(client, "bus", "source", "CheckoutBasket");
        assertThrows(IllegalStateException.class, () -> publisher.publish(Map.of("userName", "a")));
    }

    static SQSEvent sqs(String body) {
        var record = new SQSEvent.SQSMessage();
        record.setMessageId("test-message"); record.setBody(body);
        var event = new SQSEvent(); event.setRecords(List.of(record));
        return event;
    }
}
