package com.tiwalo.banking_core_api.transfer.internal;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

class TransferCapTests {
    @Test
    void servletServiceRequiresAPositiveConfiguredCap() {
        var database = mock(JdbcClient.class);
        assertThatIllegalArgumentException().isThrownBy(() -> new InternalTransfers(database, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new InternalTransfers(database, -1));
    }
}
