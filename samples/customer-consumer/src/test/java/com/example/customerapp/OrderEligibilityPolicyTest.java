package com.example.customerapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderEligibilityPolicyTest {

    private final OrderEligibilityPolicy policy = new OrderEligibilityPolicy();

    @Test
    void activeCustomerCanPlaceOrder() {
        Customer customer = customer("Alice Smith", CustomerStatus.ACTIVE);
        assertTrue(policy.canPlaceOrder(customer));
        assertEquals("", policy.describeRejection(customer));
    }

    @Test
    void suspendedCustomerCannotPlaceOrder() {
        Customer customer = customer("Bob Jones", CustomerStatus.SUSPENDED);
        assertFalse(policy.canPlaceOrder(customer));
        assertEquals("Bob Jones is suspended", policy.describeRejection(customer));
    }

    @Test
    void closedCustomerCannotPlaceOrder() {
        Customer customer = customer("Cara Diaz", CustomerStatus.CLOSED);
        assertFalse(policy.canPlaceOrder(customer));
        assertEquals("Cara Diaz is closed", policy.describeRejection(customer));
    }

    private Customer customer(String fullName, CustomerStatus status) {
        Customer customer = new Customer();
        customer.setId("c-1");
        customer.setFullName(fullName);
        customer.setStatus(status);
        return customer;
    }
}
