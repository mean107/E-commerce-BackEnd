package com.ecommerce.shared;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private Json() {}

    public static Map<String, Object> object(String json) {
        try {
            Map<String, Object> result = MAPPER.readValue(json, new TypeReference<>() {});
            if (result == null) throw new IllegalArgumentException("JSON object is required");
            return new LinkedHashMap<>(result);
        } catch (Exception e) {
            throw new IllegalArgumentException("Body must be a valid JSON object", e);
        }
    }

    public static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize response", e); }
    }
}
