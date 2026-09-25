package com.ecommerce.basket;

import java.util.Map;

@FunctionalInterface
public interface CheckoutPublisher {
    void publish(Map<String, Object> detail);
}
