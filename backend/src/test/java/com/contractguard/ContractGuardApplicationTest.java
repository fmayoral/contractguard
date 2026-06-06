package com.contractguard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ContractGuardApplicationTest {

    @Test
    void contextLoads() {
        // Boot failure fails this test; assertions live in feature tests.
    }
}
