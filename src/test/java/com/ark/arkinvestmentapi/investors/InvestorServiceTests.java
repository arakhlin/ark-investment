package com.ark.arkinvestmentapi.investors;

import com.ark.arkinvestmentapi.common.ResourceNotFoundException;
import com.ark.arkinvestmentapi.security.ClientAccess;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvestorServiceTests {
    final UUID client = UUID.randomUUID();
    final InvestorRepository repository = mock(InvestorRepository.class);
    final InvestorService service = new InvestorService(mock(ClientAccess.class), repository);

    @Test
    void replacementUpdatesBothFieldsAndPreservesIdentity() {
        Investor investor = new Investor(client, "Old", "old@example.com");
        when(repository.findByClientIdAndId(client, investor.getId())).thenReturn(Optional.of(investor));
        var result = service.replace(client, investor.getId(), new InvestorInput("New", "new@example.com"));
        assertThat(result.id()).isEqualTo(investor.getId());
        assertThat(result.name()).isEqualTo("New");
        assertThat(result.email()).isEqualTo("new@example.com");
    }

    @Test
    void missingDeleteDoesNotWrite() {
        assertThatThrownBy(() -> service.delete(client, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).delete(any());
    }
}
