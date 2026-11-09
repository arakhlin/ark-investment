package com.ark.arkinvestmentapi.transactions;

import com.ark.arkinvestmentapi.common.*;
import com.ark.arkinvestmentapi.funds.FundService;
import com.ark.arkinvestmentapi.investors.InvestorService;
import com.ark.arkinvestmentapi.participation.ParticipationService;
import com.ark.arkinvestmentapi.security.ClientAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionServiceTests {
    final UUID client = UUID.randomUUID();
    final TransactionRepository repository = mock(TransactionRepository.class);
    final ParticipationService participation = mock(ParticipationService.class);
    final TransactionAuditWriter audit = mock(TransactionAuditWriter.class);
    final TransactionService service = new TransactionService(mock(ClientAccess.class), repository, mock(FundService.class), mock(InvestorService.class), participation, audit);
    final UUID fund = UUID.randomUUID(), investor = UUID.randomUUID();

    TransactionInput input(String amount) {
        var input = new TransactionInput();
        input.setFundId(fund.toString());
        input.setInvestorId(investor.toString());
        input.setDate("2026-11-06");
        input.setAmount(amount);
        input.setType(TransactionType.CONTRIBUTION);
        return input;
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00", "+1.00", "01.00", "1", "1.0", "1.001", "1e2", "1x00", "1,00", " 1.00", "1.00\n", "10000000000000000.00", "NaN", "Infinity", "١.00"})
    void rejectsInvalidMoneyBeforeAnyPersistence(String value) {
        assertThatThrownBy(() -> service.create(client, input(value))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, participation, audit);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1.00", "9999999999999999.99"})
    void exactMoneyBoundaries(String value) {
        assertThat(TransactionValues.amount(value).toPlainString()).isEqualTo(value);
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void signsUseExactDecimalArithmetic(TransactionType type) {
        var amount = new BigDecimal("9999999999999999.99");
        boolean credit = type == TransactionType.CONTRIBUTION || type == TransactionType.INTEREST_INCOME;
        assertThat(type.isCredit()).isEqualTo(credit);
        assertThat(type.signedAmount(amount)).isEqualByComparingTo(credit ? amount : amount.negate());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01", "10000-01-01", "2026-02-29", "2026-1-01", "2026-11-06T00:00:00", "2026-11-06\n"})
    void rejectsInvalidDates(String date) {
        assertThatThrownBy(() -> TransactionValues.date(date)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingParticipationPreventsSave() {
        doThrow(new StateConflictException("Participation required")).when(participation).requireForTransaction(client, fund, investor);
        assertThatThrownBy(() -> service.create(client, input("1.00"))).isInstanceOf(StateConflictException.class);
        verifyNoInteractions(repository);
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsBothOwnershipChangesWithoutMutatingEntity() {
        var entity = new Transaction(client, fund, investor, LocalDate.of(2026, 1, 1), new BigDecimal("1.00"), TransactionType.CONTRIBUTION, "original");
        when(repository.findForUpdate(client, entity.getId())).thenReturn(Optional.of(entity));
        var input = input("2.00");
        input.setFundId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.replace(client, entity.getId(), input)).isInstanceOf(StateConflictException.class);
        input.setFundId(fund.toString());
        input.setInvestorId(UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.replace(client, entity.getId(), input)).isInstanceOf(StateConflictException.class);
        assertThat(entity.getAmount()).isEqualByComparingTo("1.00");
        assertThat(entity.getDescription()).isEqualTo("original");
        verify(repository, never()).flush();
        verifyNoInteractions(audit);
    }

    @Test
    void missingTransactionIsNotUpsertedOrDeleted() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.replace(client, id, input("1.00"))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(client, id)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(repository, never()).delete(any(Transaction.class));
        verifyNoInteractions(audit);
    }
}
