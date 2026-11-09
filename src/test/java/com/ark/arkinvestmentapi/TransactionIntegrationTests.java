package com.ark.arkinvestmentapi;

import com.ark.arkinvestmentapi.transactions.TransactionType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static com.ark.arkinvestmentapi.TestRequests.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionIntegrationTests extends PostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    UUID fund, investor;

    @BeforeEach
    void prepare() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from fund_investors");
        jdbc.update("delete from funds");
        jdbc.update("delete from investors");
        fund = UUID.randomUUID();
        investor = UUID.randomUUID();
        jdbc.update("insert into funds(client_id,id,name) values ('00000000-0000-0000-0000-000000000001',?, 'Fund')", fund);
        jdbc.update("insert into investors(client_id,id,name,email) values ('00000000-0000-0000-0000-000000000001',?, 'Investor', 'a@example.com')", investor);
        jdbc.update("insert into fund_investors(client_id,id,fund_id,investor_id) values ('00000000-0000-0000-0000-000000000001',?,?,?)", UUID.randomUUID(), fund, investor);
    }

    String body(String amount, String date, String type) {
        return "{\"fundId\":\"" + fund + "\",\"investorId\":\"" + investor + "\",\"date\":\"" + date + "\",\"amount\":" + amount + ",\"type\":\"" + type + "\"}";
    }

    String body() { return body("\"1.00\"", "2026-11-06", "CONTRIBUTION"); }

    String create(String body) throws Exception {
        var response = mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value("USD")).andReturn().getResponse();
        var id = json.readTree(response.getContentAsString()).get("id").asText();
        assertThat(response.getHeader("Location")).isEqualTo("/api/v1/clients/" + CLIENT + "/transactions/" + id);
        return id;
    }

    @ParameterizedTest
    @EnumSource(TransactionType.class)
    void allTypesRoundTripCorrectAndDelete(TransactionType type) throws Exception {
        var original = body("\"9999999999999999.99\"", "0001-01-01", type.name()).replace("}", ",\"description\":\"Original\"}");
        String id = create(original);
        var created = audit(id, "CREATE");
        assertThat(created.get("id")).isInstanceOf(UUID.class);
        assertThat(created.get("occurred_at")).isNotNull();
        assertThat(created.get("previous_amount")).isNull();
        assertThat(created.get("new_amount")).isEqualTo(new BigDecimal("9999999999999999.99"));
        assertThat(created.get("new_fund_id")).isEqualTo(fund);
        assertThat(created.get("new_investor_id")).isEqualTo(investor);
        assertThat(created.get("new_date").toString()).isEqualTo("0001-01-01");
        assertThat(created.get("new_type")).isEqualTo(type.name());
        assertThat(created.get("new_description")).isEqualTo("Original");
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value(type.name())).andExpect(jsonPath("$.amount").value("9999999999999999.99"));
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + id).contentType("application/json")
                        .content(body("\"0.01\"", "9999-12-31", "MANAGEMENT_FEE")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value("0.01"))
                .andExpect(jsonPath("$.date").value("9999-12-31")).andExpect(jsonPath("$.type").value("MANAGEMENT_FEE"))
                .andExpect(jsonPath("$.description").doesNotExist()).andExpect(jsonPath("$.fundId").value(fund.toString()));
        var updated = audit(id, "UPDATE");
        assertSnapshotEquals(created, "new_", updated, "previous_");
        assertThat(updated.get("new_amount")).isEqualTo(new BigDecimal("0.01"));
        assertThat(updated.get("new_date").toString()).isEqualTo("9999-12-31");
        assertThat(updated.get("new_type")).isEqualTo("MANAGEMENT_FEE");
        assertThat(updated.get("new_description")).isNull();
        mvc.perform(delete(pair())).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andExpect(status().isNotFound());
        mvc.perform(delete(pair())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/funds/" + fund)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/investors/" + investor)).andExpect(status().isNoContent());
        var deleted = audit(id, "DELETE");
        assertSnapshotEquals(updated, "new_", deleted, "previous_");
        assertThat(deleted.get("new_amount")).isNull();
        assertThat(auditCount(id)).isEqualTo(3);
    }

    Map<String, Object> audit(String id, String operation) {
        return jdbc.queryForMap("select * from transaction_audit where transaction_id = ?::uuid and operation = ?", id, operation);
    }

    int auditCount(String id) {
        return jdbc.queryForObject("select count(*) from transaction_audit where transaction_id = ?::uuid", Integer.class, id);
    }

    void assertSnapshotEquals(Map<String, Object> first, String firstPrefix, Map<String, Object> second, String secondPrefix) {
        for (String field : List.of("fund_id", "investor_id", "date", "amount", "type", "description"))
            assertThat(second.get(secondPrefix + field)).as(field).isEqualTo(first.get(firstPrefix + field));
    }

    String pair() { return "/api/v1/clients/" + CLIENT + "/funds/" + fund + "/investors/" + investor; }

    @ParameterizedTest
    @ValueSource(strings = {"\"0.00\"", "\"-1.00\"", "\"1.0\"", "\"1.001\"", "\"1x00\"", "\"1,00\"", "\"1e2\"", "\"01.00\"", "\"+1.00\"", "\" 1.00\"", "\"1.00\\n\"", "\"10000000000000000.00\"", "1.00", "true", "null", "{}", "[]"})
    void rejectsMalformedMoneyWithoutPersistence(String amount) throws Exception {
        int auditsBefore = jdbc.queryForObject("select count(*) from transaction_audit", Integer.class);
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body(amount, "2026-11-06", "CONTRIBUTION")))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from transaction_audit", Integer.class)).isEqualTo(auditsBefore);
    }

    int count() { return jdbc.queryForObject("select count(*) from transactions", Integer.class); }

    @Test
    void unknownOwnersMissingParticipationAndOwnershipAreDistinct() throws Exception {
        int auditsBefore = jdbc.queryForObject("select count(*) from transaction_audit", Integer.class);
        jdbc.update("delete from fund_investors");
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body().replace(fund.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body().replace(investor.toString(), UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());
        mvc.perform(put(pair())).andExpect(status().isNoContent());
        String id = create(body());
        for (UUID owner : List.of(fund, investor)) {
            mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + id).contentType("application/json").content(body().replace(owner.toString(), UUID.randomUUID().toString())))
                    .andExpect(status().isConflict());
        }
        assertThat(jdbc.queryForObject("select amount::text from transactions", String.class)).isEqualTo("1.00");
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + UUID.randomUUID()).contentType("application/json").content(body())).andExpect(status().isNotFound());
        assertThat(auditCount(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from transaction_audit", Integer.class)).isEqualTo(auditsBefore + 1);
    }

    @Test
    void filtersCombineAndDatesAreInclusiveWithStablePagination() throws Exception {
        create(body("\"1.00\"", "2024-02-28", "CONTRIBUTION"));
        create(body("\"2.00\"", "2024-02-29", "CONTRIBUTION"));
        create(body("\"3.00\"", "2024-02-29", "CONTRIBUTION"));
        create(body("\"4.00\"", "2024-03-01", "DISTRIBUTION"));
        var expected = jdbc.queryForList("select id::text from transactions order by transaction_date desc,id", String.class);
        for (int p = 0; p < 4; p++)
            mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("page", "" + p).param("size", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(expected.get(p)))
                    .andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.totalPages").value(4));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("fundId", fund.toString()).param("investorId", investor.toString())
                        .param("type", "CONTRIBUTION").param("from", "2024-02-29").param("to", "2024-02-29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("to", "2024-02-28")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("from", "2024-03-01")).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("page", "100")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("fundId", UUID.randomUUID().toString()).param("from", "9999-12-31")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("investorId", UUID.randomUUID().toString())).andExpect(status().isNotFound());
        UUID other = UUID.randomUUID();
        jdbc.update("insert into funds(client_id,id,name) values ('00000000-0000-0000-0000-000000000001',?, 'Other')", other);
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("fundId", other.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        UUID secondInvestor = UUID.randomUUID();
        jdbc.update("insert into investors(client_id,id,name,email) values ('00000000-0000-0000-0000-000000000001',?, 'Other', 'b@example.com')", secondInvestor);
        jdbc.update("insert into fund_investors(client_id,id,fund_id,investor_id) values ('00000000-0000-0000-0000-000000000001',?,?,?)", UUID.randomUUID(), fund, secondInvestor);
        create(body("\"5.00\"", "2024-02-29", "CONTRIBUTION").replace(investor.toString(), secondInvestor.toString()));
        create(body("\"6.00\"", "2024-02-29", "DISTRIBUTION"));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("fundId", fund.toString()).param("investorId", investor.toString())
                        .param("type", "CONTRIBUTION").param("from", "2024-02-29").param("to", "2024-02-29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000-01-01", "10000-01-01", "2026-02-29", "2026-1-01", "2026-11-06T00:00:00"})
    void rejectsBadDatesInBodiesAndFilters(String value) throws Exception {
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body("\"1.00\"", value, "CONTRIBUTION"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("from", value)).andExpect(status().isBadRequest());
    }

    @Test
    void validationAndHttpErrors() throws Exception {
        int auditsBefore = jdbc.queryForObject("select count(*) from transaction_audit", Integer.class);
        for (String invalid : List.of("{}", "null", body().replace("CONTRIBUTION", "UNKNOWN"), body().replace(fund.toString(), "1-1-1-1-1"),
                body().replace("}", ",\"description\":null}"), body().replace("}", ",\"currency\":\"USD\"}"),
                body().replace("}", ",\"description\":\"" + "x".repeat(1001) + "\"}"), body().replace("\"type\":\"CONTRIBUTION\"", "\"type\":null")))
            mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param("from", "2026-11-07").param("to", "2026-11-06")).andExpect(status().isBadRequest());
        for (String[] param : List.of(new String[]{"page", "-1"}, new String[]{"size", "101"}, new String[]{"size", "0"}, new String[]{"type", "bad"}, new String[]{"fundId", "1-1-1-1-1"}))
            mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions").param(param[0], param[1])).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions/bad")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("text/plain").content(body())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions")).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/transactions/" + UUID.randomUUID())).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from transaction_audit", Integer.class)).isEqualTo(auditsBefore);
    }

    @Test
    void rejectedCorrectionLeavesOriginalUnchanged() throws Exception {
        var id = create(body());
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + id).contentType("application/json").content(body("\"1.001\"", "2020-01-01", "DISTRIBUTION"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andExpect(jsonPath("$.amount").value("1.00")).andExpect(jsonPath("$.type").value("CONTRIBUTION")).andExpect(jsonPath("$.date").value("2026-11-06"));
        assertThat(auditCount(id)).isEqualTo(1);
    }

    @Test
    void databaseConstraintsProtectDirectWrites() {
        String sql = "insert into transactions(client_id,id,fund_id,investor_id,transaction_date,amount,type) values ('00000000-0000-0000-0000-000000000001',?,?,?,?::date,?::numeric,?)";
        for (String amount : List.of("0.00", "-0.01", "NaN", "10000000000000000.00"))
            assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), fund, investor, "2026-11-06", amount, "CONTRIBUTION")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), fund, investor, "2026-11-06", "1.00", "UNKNOWN")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), fund, investor, "0001-01-01 BC", "1.00", "CONTRIBUTION")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("delete from fund_investors");
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), fund, investor, "2026-11-06", "1.00", "CONTRIBUTION")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count()).isZero();
    }

    @Test
    void concurrentCorrectionsPersistOneCompleteReplacement() throws Exception {
        String id = create(body());
        String firstBody = body("\"2.00\"", "2020-01-01", "DISTRIBUTION");
        String secondBody = body("\"3.00\"", "2030-01-01", "INTEREST_INCOME");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (String replacement : List.of(firstBody, secondBody))
                futures.add(executor.submit(() -> { start.await(); return mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + id).contentType("application/json").content(replacement)).andReturn().getResponse().getStatus(); }));
            start.countDown();
            for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        }
        var row = jdbc.queryForMap("select amount::text as amount,transaction_date::text as date,type from transactions");
        assertThat(List.of(row.get("amount"), row.get("date"), row.get("type")))
                .isIn(List.of("2.00", "2020-01-01", "DISTRIBUTION"), List.of("3.00", "2030-01-01", "INTEREST_INCOME"));
        var updates = jdbc.queryForList("select * from transaction_audit where transaction_id = ?::uuid and operation = 'UPDATE'", id);
        assertThat(updates).hasSize(2);
        var first = updates.stream().filter(a -> a.get("previous_amount").equals(new BigDecimal("1.00"))).findFirst().orElseThrow();
        var second = updates.stream().filter(a -> a != first).findFirst().orElseThrow();
        assertSnapshotEquals(first, "new_", second, "previous_");
        assertThat(second.get("new_amount").toString()).isEqualTo(row.get("amount"));
        assertThat(auditCount(id)).isEqualTo(3);
    }

    @Test
    void concurrentDeleteAndCorrectionHaveDefinedOutcomes() throws Exception {
        String id = create(body());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var update = executor.submit(() -> { start.await(); return mvc.perform(put("/api/v1/clients/" + CLIENT + "/transactions/" + id).contentType("application/json").content(body("\"2.00\"", "2020-01-01", "DISTRIBUTION"))).andReturn().getResponse().getStatus(); });
            var remove = executor.submit(() -> { start.await(); return mvc.perform(delete("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andReturn().getResponse().getStatus(); });
            start.countDown();
            assertThat(update.get(20, TimeUnit.SECONDS)).isIn(200, 404);
            assertThat(remove.get(20, TimeUnit.SECONDS)).isEqualTo(204);
            boolean corrected = update.get() == 200;
            assertThat(auditCount(id)).isEqualTo(corrected ? 3 : 2);
            assertSnapshotEquals(audit(id, corrected ? "UPDATE" : "CREATE"), "new_", audit(id, "DELETE"), "previous_");
        }
        assertThat(count()).isZero();
    }

    @Test
    void concurrentCreationAndParticipationRemovalPreserveIntegrity() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var create = executor.submit(() -> { start.await(); return mvc.perform(post("/api/v1/clients/" + CLIENT + "/transactions").contentType("application/json").content(body())).andReturn().getResponse().getStatus(); });
            var remove = executor.submit(() -> { start.await(); return mvc.perform(delete(pair())).andReturn().getResponse().getStatus(); });
            start.countDown();
            int created = create.get(20, TimeUnit.SECONDS), removed = remove.get(20, TimeUnit.SECONDS);
            assertThat(List.of(created, removed)).isIn(List.of(201, 409), List.of(409, 204));
            assertThat(count()).isEqualTo(created == 201 ? 1 : 0);
            assertThat(jdbc.queryForObject("select count(*) from transaction_audit where new_fund_id = ?", Integer.class, fund)).isEqualTo(created == 201 ? 1 : 0);
            assertThat(jdbc.queryForObject("select count(*) from transactions t left join fund_investors p on t.fund_id=p.fund_id and t.investor_id=p.investor_id where p.id is null", Integer.class)).isZero();
        }
    }

    @Test
    void concurrentDeletesProduceOnlyOneDeleteAudit() throws Exception {
        String id = create(body());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> remove = () -> {
                start.await();
                return mvc.perform(delete("/api/v1/clients/" + CLIENT + "/transactions/" + id)).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(remove);
            var second = executor.submit(remove);
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(204, 404);
        }
        assertThat(auditCount(id)).isEqualTo(2);
        assertSnapshotEquals(audit(id, "CREATE"), "new_", audit(id, "DELETE"), "previous_");
    }
}
