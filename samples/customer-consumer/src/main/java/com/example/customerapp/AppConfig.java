package com.example.customerapp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public CustomerClient customerClient(RestTemplate restTemplate,
            @Value("${customer.api.base-url}") String baseUrl) {
        return new CustomerClient(restTemplate, baseUrl);
    }

    @Bean
    public OrderEligibilityPolicy orderEligibilityPolicy() {
        return new OrderEligibilityPolicy();
    }
}
