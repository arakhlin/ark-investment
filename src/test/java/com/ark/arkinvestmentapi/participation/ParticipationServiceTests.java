package com.ark.arkinvestmentapi.participation;

import com.ark.arkinvestmentapi.common.ResourceNotFoundException;
import com.ark.arkinvestmentapi.funds.*;
import com.ark.arkinvestmentapi.investors.*;
import com.ark.arkinvestmentapi.security.ClientAccess;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParticipationServiceTests {
    final UUID client = UUID.randomUUID();
    final ParticipationRepository repository = mock(ParticipationRepository.class);
    final FundService funds = mock(FundService.class);
    final InvestorService investors = mock(InvestorService.class);
    final ParticipationService service = new ParticipationService(mock(ClientAccess.class), repository, funds, investors);

    @Test
    void missingFundStopsBeforeMembershipMutation() {
        when(funds.lock(eq(client), any())).thenThrow(new ResourceNotFoundException("Fund not found"));
        assertThatThrownBy(() -> service.add(client, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void missingInvestorStopsRemovalEvenForAbsentMembership() {
        UUID fundId = UUID.randomUUID();
        when(investors.lock(eq(client), any())).thenThrow(new ResourceNotFoundException("Investor not found"));
        assertThatThrownBy(() -> service.remove(client, fundId, UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(repository);
    }
}
