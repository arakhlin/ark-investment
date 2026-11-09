package com.ark.arkinvestmentapi;

import com.ark.arkinvestmentapi.reporting.*;
import com.ark.arkinvestmentapi.funds.FundService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ark.arkinvestmentapi.TestRequests.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ReportIntegrationTests extends PostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportService reports;
    @MockitoSpyBean FundService funds;
    UUID fund, investor;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from fund_investors");
        jdbc.update("delete from funds");
        jdbc.update("delete from investors");
        fund = newFund(); investor = newInvestor(); pair(fund, investor);
    }

    UUID newFund() {
        var id = UUID.randomUUID();
        jdbc.update("insert into funds(client_id,id,name) values ('00000000-0000-0000-0000-000000000001',?, 'Fund')", id); return id;
    }
    UUID newInvestor() {
        var id = UUID.randomUUID();
        jdbc.update("insert into investors(client_id,id,name,email) values ('00000000-0000-0000-0000-000000000001',?, 'Investor', 'i@example.com')", id); return id;
    }
    void pair(UUID f, UUID i) {
        jdbc.update("insert into fund_investors(client_id,id,fund_id,investor_id) values ('00000000-0000-0000-0000-000000000001',?,?,?)", UUID.randomUUID(), f, i);
    }
    UUID entry(UUID f, UUID i, String date, String amount, String type) {
        var id = UUID.randomUUID();
        jdbc.update("insert into transactions(client_id,id,fund_id,investor_id,transaction_date,amount,type) values ('00000000-0000-0000-0000-000000000001',?,?,?,cast(? as date),?,?)",
                id, f, i, date, new BigDecimal(amount), type); return id;
    }
    String fundPath() { return "/api/v1/clients/" + CLIENT + "/funds/" + fund + "/report"; }
    String investorPath() { return "/api/v1/clients/" + CLIENT + "/investors/" + investor + "/report"; }

    @Test
    void emptyResponsesIncludeNullsAndFiveBucketsEvenWithoutMembership() throws Exception {
        mvc.perform(get(fundPath())).andExpect(status().isOk())
                .andExpect(content().json("{\"fundId\":\"" + fund + "\",\"currency\":\"USD\",\"from\":null,\"to\":null,\"totals\":{\"openingBalance\":\"0.00\",\"totalCredits\":\"0.00\",\"totalDebits\":\"0.00\",\"netMovement\":\"0.00\",\"closingBalance\":\"0.00\",\"transactionCount\":0}}"))
                .andExpect(jsonPath("$.totals.byType.length()").value(5));
        mvc.perform(get(investorPath())).andExpect(status().isOk())
                .andExpect(content().json("{\"fundId\":null,\"from\":null,\"to\":null}"));
        mvc.perform(get(investorPath()).param("fundId", newFund().toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.closingBalance").value("0.00"));
    }

    @Test
    void reportNamesMatchPersistedEntitiesForFilteredAndUnfilteredScopes() throws Exception {
        var fundName = "Growth & Income Fund";
        var investorName = "Alex Morgan";
        jdbc.update("update funds set name=? where id=?", fundName, fund);
        jdbc.update("update investors set name=? where id=?", investorName, investor);
        var otherFund = newFund();
        pair(otherFund, investor);
        entry(fund, investor, "2024-01-01", "10.00", "CONTRIBUTION");
        entry(otherFund, investor, "2024-01-01", "20.00", "CONTRIBUTION");

        mvc.perform(get(fundPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.fundId").value(fund.toString()))
                .andExpect(jsonPath("$.fundName").value(fundName));
        mvc.perform(get(investorPath()).param("fundId", fund.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.investorId").value(investor.toString()))
                .andExpect(jsonPath("$.investorName").value(investorName))
                .andExpect(jsonPath("$.fundId").value(fund.toString()))
                .andExpect(jsonPath("$.fundName").value(fundName))
                .andExpect(jsonPath("$.totals.closingBalance").value("10.00"));
        mvc.perform(get(investorPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.investorId").value(investor.toString()))
                .andExpect(jsonPath("$.investorName").value(investorName))
                .andExpect(content().json("{\"fundId\":null,\"fundName\":null}"))
                .andExpect(jsonPath("$.totals.closingBalance").value("30.00"));
    }

    @Test
    void inclusiveLeapDayAllTypesOpeningAndFutureDates() throws Exception {
        entry(fund, investor, "2024-02-28", "100.00", "CONTRIBUTION");
        entry(fund, investor, "2024-02-29", "10.01", "CONTRIBUTION");
        entry(fund, investor, "2024-02-29", "0.02", "INTEREST_INCOME");
        entry(fund, investor, "2024-02-29", "20.00", "DISTRIBUTION");
        entry(fund, investor, "2024-02-29", "0.03", "GENERAL_EXPENSE");
        entry(fund, investor, "2024-02-29", "0.04", "MANAGEMENT_FEE");
        entry(fund, investor, "9999-12-31", "200.00", "DISTRIBUTION");
        for (var path : List.of(fundPath(), investorPath())) {
            mvc.perform(get(path).param("from", "2024-02-29").param("to", "2024-02-29"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.from").value("2024-02-29"))
                    .andExpect(jsonPath("$.totals.openingBalance").value("100.00"))
                    .andExpect(jsonPath("$.totals.totalCredits").value("10.03"))
                    .andExpect(jsonPath("$.totals.totalDebits").value("20.07"))
                    .andExpect(jsonPath("$.totals.netMovement").value("-10.04"))
                    .andExpect(jsonPath("$.totals.closingBalance").value("89.96"))
                    .andExpect(jsonPath("$.totals.transactionCount").value(5))
                    .andExpect(jsonPath("$.totals.byType[0].transactionCount").value(1))
                    .andExpect(jsonPath("$.totals.byType[1].transactionCount").value(1))
                    .andExpect(jsonPath("$.totals.byType[2].transactionCount").value(1))
                    .andExpect(jsonPath("$.totals.byType[3].transactionCount").value(1))
                    .andExpect(jsonPath("$.totals.byType[4].transactionCount").value(1));
            mvc.perform(get(path)).andExpect(jsonPath("$.totals.closingBalance").value("-110.04"));
            mvc.perform(get(path).param("to", "2024-02-28")).andExpect(jsonPath("$.totals.closingBalance").value("100.00"));
            mvc.perform(get(path).param("from", "2025-01-01")).andExpect(jsonPath("$.totals.openingBalance").value("89.96"));
        }
    }

    @Test
    void scopesAndWideAggregates() {
        var f2 = newFund(); var i2 = newInvestor(); pair(fund, i2); pair(f2, investor);
        entry(fund, investor, "0001-01-01", "9999999999999999.99", "CONTRIBUTION");
        entry(fund, i2, "2026-01-01", "9999999999999999.99", "CONTRIBUTION");
        entry(f2, investor, "2026-01-01", "0.01", "MANAGEMENT_FEE");
        authenticate();
        assertThat(reports.fund(CLIENT, fund, null, null).totals().closingBalance()).isEqualTo("19999999999999999.98");
        authenticate();
        assertThat(reports.investor(CLIENT, investor, null, null, null).totals().closingBalance()).isEqualTo("9999999999999999.98");
        authenticate();
        assertThat(reports.investor(CLIENT, investor, fund, null, null).totals().closingBalance()).isEqualTo("9999999999999999.99");
    }

    @Test
    void correctionsAndDeletionRecomputeHistory() throws Exception {
        var id = entry(fund, investor, "2024-01-01", "10.00", "CONTRIBUTION");
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + id).contentType("application/json").content("""
                {"fundId":"%s","investorId":"%s","date":"2024-02-01","amount":"3.00","type":"MANAGEMENT_FEE"}
                """.formatted(fund, investor))).andExpect(status().isOk());
        authenticate();
        assertThat(reports.fund(CLIENT, fund,"2024-02-01","2024-02-01").totals().closingBalance()).isEqualTo("-3.00");
        authenticate();
        assertThat(reports.fund(CLIENT, fund,null,"2024-01-31").totals().transactionCount()).isZero();
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andExpect(status().isNoContent());
        authenticate();
        assertThat(reports.investor(CLIENT, investor,null,null,null).totals().closingBalance()).isEqualTo("0.00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "2024-02-30", "2023-02-29", "0000-01-01", "10000-01-01", "2024-2-01", "2024-01-01T00:00:00Z"})
    void invalidDates(String date) throws Exception {
        for (var path : List.of(fundPath(), investorPath())) for (var bound : List.of("from", "to"))
            mvc.perform(get(path).param(bound,date)).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void invalidRangeAndMissingOwners() throws Exception {
        for (var path : List.of(fundPath(), investorPath()))
            mvc.perform(get(path).param("from","2025-01-01").param("to","2024-01-01")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/" + UUID.randomUUID() + "/report")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/investors/" + UUID.randomUUID() + "/report")).andExpect(status().isNotFound());
        mvc.perform(get(investorPath()).param("fundId",UUID.randomUUID().toString())).andExpect(status().isNotFound());
        mvc.perform(get(investorPath()).param("fundId","1-1-1-1-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/bad/report")).andExpect(status().isBadRequest());
    }

    @Test
    void reportSnapshotSurvivesCommittedInsertCorrectionAndDeletion() throws Exception {
        var corrected = entry(fund, investor,"2024-01-01","10.00","CONTRIBUTION");
        var deleted = entry(fund, investor,"2024-02-01","2.00","DISTRIBUTION");
        var snapshotTaken = new CountDownLatch(1); var committed = new CountDownLatch(1);
        doAnswer(call -> {
            var owner = call.callRealMethod(); // first SELECT establishes the report's snapshot
            snapshotTaken.countDown();
            assertThat(committed.await(20, TimeUnit.SECONDS)).isTrue();
            return owner;
        }).when(funds).require(CLIENT, fund);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(() -> { authenticate(); return reports.fund(CLIENT, fund,"2024-02-01",null); });
            assertThat(snapshotTaken.await(20,TimeUnit.SECONDS)).isTrue();
            jdbc.update("update transactions set amount=30.00 where id=?", corrected);
            jdbc.update("delete from transactions where id=?", deleted);
            entry(fund,investor,"2024-02-01","5.00","INTEREST_INCOME");
            committed.countDown();
            var old = pending.get(20,TimeUnit.SECONDS).totals();
            assertThat(old.openingBalance()).isEqualTo("10.00");
            assertThat(old.totalDebits()).isEqualTo("2.00");
            assertThat(old.totalCredits()).isEqualTo("0.00");
            assertThat(old.closingBalance()).isEqualTo("8.00");
        } finally { committed.countDown(); reset(funds); }
        authenticate();
        var fresh = reports.fund(CLIENT, fund,"2024-02-01",null).totals();
        assertThat(fresh.openingBalance()).isEqualTo("30.00");
        assertThat(fresh.totalDebits()).isEqualTo("0.00");
        assertThat(fresh.closingBalance()).isEqualTo("35.00");
    }
}
