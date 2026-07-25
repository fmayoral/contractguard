package com.contractguard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ContractGuardApplicationTest {

    // Standard Spring Boot idiom: the only thing under test is "the context wires up at all",
    // which a failed boot already fails this test for -- there is nothing left to assert.
    @SuppressWarnings("java:S2699")
    @Test
    void contextLoads() {
        // Boot failure fails this test; assertions live in feature tests.
    }
}
