package com.example.customerapp;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

class CustomerClientTest {

    @Test
    void fetchesCustomerByIdFromCustomerEndpoint() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        CustomerClient client = new CustomerClient(restTemplate, "https://api.example.com");

        server.expect(requestTo("https://api.example.com/customers/42"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"id\":\"42\",\"fullName\":\"Alice Smith\",\"status\":\"ACTIVE\"}",
                        MediaType.APPLICATION_JSON));

        Customer customer = client.fetchCustomer("42");

        assertEquals("42", customer.getId());
        assertEquals("Alice Smith", customer.getFullName());
        assertEquals(CustomerStatus.ACTIVE, customer.getStatus());
        server.verify();
    }
}
