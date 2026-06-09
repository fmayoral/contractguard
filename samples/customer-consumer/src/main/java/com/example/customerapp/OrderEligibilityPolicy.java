package com.example.customerapp;

/** Business rule: only customers in good standing may place orders. */
public class OrderEligibilityPolicy {

    public boolean canPlaceOrder(Customer customer) {
        return switch (customer.getStatus()) {
            case ACTIVE -> true;
            case SUSPENDED -> false;
            case CLOSED -> false;
        };
    }

    public String describeRejection(Customer customer) {
        return switch (customer.getStatus()) {
            case ACTIVE -> "";
            case SUSPENDED -> customer.getFullName() + " is suspended";
            case CLOSED -> customer.getFullName() + " is closed";
        };
    }
}
