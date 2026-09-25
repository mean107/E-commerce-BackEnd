package com.ecommerce.shared;

import com.amazonaws.services.lambda.runtime.*;
import com.amazonaws.services.lambda.runtime.events.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public abstract class HttpHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    public final APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try { return route(event); }
        catch (IllegalArgumentException e) { return response(400, Map.of("message", e.getMessage())); }
        catch (Exception e) {
            if (context != null) context.getLogger().log("Request failed: " + e + "\n");
            return response(500, Map.of("message", "Internal server error"));
        }
    }
    protected abstract APIGatewayProxyResponseEvent route(APIGatewayProxyRequestEvent event);
    protected static APIGatewayProxyResponseEvent response(int status, Object body) {
        return new APIGatewayProxyResponseEvent().withStatusCode(status)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(status == 204 ? "" : Json.write(body));
    }
    protected static Map<String, Object> body(APIGatewayProxyRequestEvent event) {
        if (event.getBody() == null || event.getBody().isBlank()) return new LinkedHashMap<>();
        String text = event.getBody();
        if (Boolean.TRUE.equals(event.getIsBase64Encoded()))
            text = new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        return Json.object(text);
    }
    protected static String path(APIGatewayProxyRequestEvent event, String key) {
        return event.getPathParameters() == null ? null : event.getPathParameters().get(key);
    }
    public static String userName(Map<String, Object> item) {
        Object value = item.get("userName");
        if (value == null || "".equals(value)) value = item.get("username");
        if (!(value instanceof String s) || s.isBlank())
            throw new IllegalArgumentException("userName is required and must be a non-empty string");
        return s;
    }
    protected static APIGatewayProxyResponseEvent found(Object item, String entity) {
        return item == null ? response(404, Map.of("message", entity + " not found")) : response(200, item);
    }
}
