package com.ecommerce.shared;

import java.util.List;
import java.util.Map;

/** Injectable boundary: tests use an in-memory implementation, production uses DynamoDB. */
public interface ItemStore {
    Map<String, Object> get(Map<String, Object> key);
    void put(Map<String, Object> item);
    void delete(Map<String, Object> key);
    List<Map<String, Object>> scan();
    List<Map<String, Object>> query(String key, String value);
}
