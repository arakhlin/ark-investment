package com.ark.arkinvestmentapi.funds;

import com.ark.arkinvestmentapi.common.*;
import com.ark.arkinvestmentapi.security.ClientAccess;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FundServiceTests {
    final UUID client = UUID.randomUUID();
    final FundRepository repository = mock(FundRepository.class);
    final FundService service = new FundService(mock(ClientAccess.class), repository);

    @Test
    void replacementClearsOmittedDescriptionAndKeepsIdentityAndCurrency() {
        Fund fund = new Fund(client, "Old", "Description");
        when(repository.findByClientIdAndId(client, fund.getId())).thenReturn(Optional.of(fund));
        var result = service.replace(client, fund.getId(), new FundInput("New", null));
        assertThat(result.id()).isEqualTo(fund.getId());
        assertThat(result.description()).isNull();
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.name()).isEqualTo("New");
        verify(repository).flush();
    }

    @Test
    void missingReplacementDoesNotUpsert() {
        assertThatThrownBy(() -> service.replace(client, UUID.randomUUID(), new FundInput("New", null))).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void paginationEnforcesBounds() {
        assertThatThrownBy(() -> Pagination.request(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pagination.request(0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Pagination.request(0, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
