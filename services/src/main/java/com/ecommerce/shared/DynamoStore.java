package com.ecommerce.shared;

import java.math.BigDecimal;
import java.util.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public final class DynamoStore implements ItemStore {
    private final DynamoDbClient client;
    private final String table;
    public DynamoStore(DynamoDbClient client, String table) {
        this.client = client;
        this.table = Objects.requireNonNull(table, "DYNAMODB_TABLE_NAME is required");
    }
    public static DynamoStore fromEnvironment() {
        return new DynamoStore(DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build(),
                System.getenv("DYNAMODB_TABLE_NAME"));
    }
    public Map<String, Object> get(Map<String, Object> key) {
        var item = client.getItem(GetItemRequest.builder().tableName(table)
                .key(encode(key)).consistentRead(true).build()).item();
        return item.isEmpty() ? null : decode(item);
    }
    public void put(Map<String, Object> item) {
        client.putItem(PutItemRequest.builder().tableName(table).item(encode(item)).build());
    }
    public void delete(Map<String, Object> key) {
        client.deleteItem(DeleteItemRequest.builder().tableName(table).key(encode(key)).build());
    }
    public List<Map<String, Object>> scan() {
        return client.scanPaginator(ScanRequest.builder().tableName(table).build())
                .items().stream().map(DynamoStore::decode).toList();
    }
    public List<Map<String, Object>> query(String key, String value) {
        return client.queryPaginator(QueryRequest.builder().tableName(table)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", key))
                .expressionAttributeValues(encode(Map.of(":pk", value))).build())
                .items().stream().map(DynamoStore::decode).toList();
    }
    public static Map<String, AttributeValue> encode(Map<String, Object> item) {
        Map<String, AttributeValue> result = new LinkedHashMap<>();
        item.forEach((k, v) -> result.put(k, attribute(v)));
        return result;
    }
    private static AttributeValue attribute(Object value) {
        if (value == null) return AttributeValue.builder().nul(true).build();
        if (value instanceof String s) return AttributeValue.builder().s(s).build();
        if (value instanceof Number n) return AttributeValue.builder().n(n.toString()).build();
        if (value instanceof Boolean b) return AttributeValue.builder().bool(b).build();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(k.toString(), v));
            return AttributeValue.builder().m(encode(normalized)).build();
        }
        if (value instanceof List<?> list)
            return AttributeValue.builder().l(list.stream().map(DynamoStore::attribute).toList()).build();
        throw new IllegalArgumentException("Unsupported item value: " + value.getClass());
    }
    public static Map<String, Object> decode(Map<String, AttributeValue> item) {
        Map<String, Object> result = new LinkedHashMap<>();
        item.forEach((k, v) -> result.put(k, value(v)));
        return result;
    }
    private static Object value(AttributeValue v) {
        if (Boolean.TRUE.equals(v.nul())) return null;
        if (v.s() != null) return v.s();
        if (v.n() != null) return new BigDecimal(v.n());
        if (v.bool() != null) return v.bool();
        if (v.hasM()) return decode(v.m());
        if (v.hasL()) return v.l().stream().map(DynamoStore::value).toList();
        throw new IllegalArgumentException("Unsupported DynamoDB attribute");
    }
}
