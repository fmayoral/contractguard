package com.example.customerapp;

import org.springframework.web.client.RestTemplate;

/** HTTP client for the Customer API. */
public class CustomerClient {

    static final String CUSTOMER_BY_ID_PATH = "/customers/{id}";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CustomerClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public Customer fetchCustomer(String id) {
        return restTemplate.getForObject(baseUrl + CUSTOMER_BY_ID_PATH, Customer.class, id);
    }
}
