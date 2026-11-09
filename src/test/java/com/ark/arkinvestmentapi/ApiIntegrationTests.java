package com.ark.arkinvestmentapi;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static com.ark.arkinvestmentapi.TestRequests.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTests extends PostgresTestSupport {
    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;

    @BeforeEach
    void clean() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from fund_investors");
        jdbc.update("delete from funds");
        jdbc.update("delete from investors");
    }

    JsonNode create(String resource, String body) throws Exception {
        var result = mvc.perform(post("/api/v1/clients/" + CLIENT + "/" + resource).contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn();
        var node = json.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/clients/" + CLIENT + "/" + resource + "/" + node.get("id").asText());
        return node;
    }

    String fund() throws Exception {
        return create("funds", "{\"name\":\"Fund\",\"description\":\"Text\"}").get("id").asText();
    }

    String investor() throws Exception {
        return create("investors", "{\"name\":\"Investor\",\"email\":\"a@example.com\"}").get("id").asText();
    }

    @Test
    void fundCrudAndReplacement() throws Exception {
        String id = fund();
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("USD"));
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/funds/" + id).contentType("application/json").content("{\"name\":\"Replaced\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Replaced")).andExpect(jsonPath("$.description").doesNotExist());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/funds/" + id)).andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/" + id)).andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void investorCrudAndNonUniqueNamesAndEmails() throws Exception {
        String id = investor();
        investor();
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/investors/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("a@example.com"));
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/investors/" + id).contentType("application/json").content("{\"name\":\"New\",\"email\":\"b@example.com\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("b@example.com"));
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/investors/" + id)).andExpect(status().isNoContent());
    }

    @Test
    void participationIsManyToManyIdempotentAndRestrictsOwnerDeletion() throws Exception {
        String f = fund(), f2 = fund(), i = investor(), i2 = investor();
        String pair = "/api/v1/clients/" + CLIENT + "/funds/" + f + "/investors/" + i;
        mvc.perform(put(pair)).andExpect(status().isNoContent());
        mvc.perform(put(pair)).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/funds/" + f + "/investors/" + i2)).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/funds/" + f2 + "/investors/" + i)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/" + f + "/investors").param("size", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/investors/" + i + "/funds")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/funds/" + f)).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/investors/" + i)).andExpect(status().isConflict());
        mvc.perform(delete(pair)).andExpect(status().isNoContent());
        mvc.perform(delete(pair)).andExpect(status().isNoContent());
    }

    @Test
    void pairWithLedgerHistoryCannotBeRemoved() throws Exception {
        String f = fund(), i = investor(), pair = "/api/v1/clients/" + CLIENT + "/funds/" + f + "/investors/" + i;
        mvc.perform(put(pair)).andExpect(status().isNoContent());
        // Fixture only: Transactions API is deliberately outside this phase.
        jdbc.update("insert into transactions(client_id,id,fund_id,investor_id,transaction_date,amount,type) values ('00000000-0000-0000-0000-000000000001',?,?,?,DATE '2026-11-06',1.00,'CONTRIBUTION')", UUID.randomUUID(), UUID.fromString(f), UUID.fromString(i));
        mvc.perform(delete(pair)).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        assertThat(jdbc.queryForObject("select count(*) from fund_investors", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\" \"}", "{\"name\":1}", "{\"name\":true}", "{\"name\":\"x\",\"description\":null}", "{\"name\":\"x\",\"currency\":\"USD\"}", "{", "null"})
    void strictFundInput(String body) throws Exception {
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/funds").contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\"x\"}", "{\"name\":\"x\",\"email\":null}", "{\"name\":\"x\",\"email\":\"bad\"}", "{\"name\":\"x\",\"email\":\"\"}", "{\"name\":\"x\",\"email\":42}"})
    void strictInvestorInput(String body) throws Exception {
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/investors").contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void lengthsAndMediaType() throws Exception {
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/funds").contentType("text/plain").content("x")).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.status").value(415));
        create("funds", "{\"name\":\"" + "x".repeat(200) + "\",\"description\":\"" + "x".repeat(1000) + "\"}");
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/funds").contentType("application/json").content("{\"name\":\"" + "x".repeat(201) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/clients/" + CLIENT + "/funds").contentType("application/json").content("{\"name\":\"x\",\"description\":\"" + "x".repeat(1001) + "\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void paginationAndOrdering() throws Exception {
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(20)).andExpect(jsonPath("$.totalPages").value(0));
        fund();
        fund();
        fund();
        var expected = jdbc.queryForList("select id::text from funds order by id", String.class);
        for (int page = 0; page < 3; page++) {
            mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds").param("page", "" + page).param("size", "1")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(expected.get(page))).andExpect(jsonPath("$.totalElements").value(3));
        }
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/investors").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds").param("page", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void missingOwnersAndInvalidUuids() throws Exception {
        String missing = UUID.randomUUID().toString(), i = investor();
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/1-1-1-1-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/investors/invalid")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/funds/" + missing).contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/investors/" + missing)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/clients/" + CLIENT + "/funds/" + missing + "/investors/" + i)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/clients/" + CLIENT + "/funds/" + missing + "/investors/" + i)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/funds/" + missing + "/investors")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/clients/" + CLIENT + "/investors/" + missing + "/funds")).andExpect(status().isNotFound());
    }

    @Test
    void concurrentAssociationRequestsBothSucceed() throws Exception {
        String pair = "/api/v1/clients/" + CLIENT + "/funds/" + fund() + "/investors/" + investor();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> action = () -> {
                start.await();
                return mvc.perform(put(pair)).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(204);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(jdbc.queryForObject("select count(*) from fund_investors", Integer.class)).isEqualTo(1);
    }
}
