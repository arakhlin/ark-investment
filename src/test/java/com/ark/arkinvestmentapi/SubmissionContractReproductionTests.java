package com.ark.arkinvestmentapi;

import com.ark.arkinvestmentapi.common.ResourceNotFoundException;
import com.ark.arkinvestmentapi.common.StateConflictException;
import com.ark.arkinvestmentapi.security.AdministrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Regression coverage for explicit-null ownership and framework HTTP error responses. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SubmissionContractReproductionTests extends PostgresTestSupport {
    @Autowired MockMvc mvc;
    @MockitoSpyBean AdministrationService administration;
    String bearer;

    @BeforeEach
    void prepareArkAdministrator() throws Exception {
        var id = UUID.randomUUID();
        clientJdbc.update("insert into app_users(id,issuer,subject,user_type,role,status) values (?,'https://issuer.test',?,'ARK','ARK_ADMIN','ACTIVE')", id, id.toString());
        bearer = TestTokens.token(id.toString(), "https://issuer.test", "ark-api", Instant.now().plusSeconds(300), false);
        // Requests authenticate through the real bearer-token filter, not the base fixture context.
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @CsvSource({"ARK,omitted,201", "ARK,null,400", "ARK,uuid,400", "CLIENT,omitted,400", "CLIENT,null,400", "CLIENT,uuid,201"})
    void provisioningDistinguishesOwnershipValues(String type, String ownership, int expectedStatus) throws Exception {
        var subject = UUID.randomUUID().toString();
        clientJdbc.update("insert into trusted_identities(issuer,subject,user_type,client_id) values ('https://issuer.test',?,?,?)",
                subject, type, type.equals("CLIENT") ? CLIENT : null);
        String clientField = switch (ownership) {
            case "null" -> ",\"clientId\":null";
            case "uuid" -> ",\"clientId\":\"" + CLIENT + "\"";
            default -> "";
        };
        String body = "{\"issuer\":\"https://issuer.test\",\"subject\":\"" + subject
                + "\",\"userType\":\"" + type + "\",\"role\":\"" + type
                + "_REPORT_VIEWER\",\"status\":\"ACTIVE\"" + clientField + "}";
        var result = mvc.perform(auth(post("/api/v1/users")).contentType("application/json").content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        if (expectedStatus == 400) {
            problem(result, 400, "BadRequest", "Invalid JSON, field value, UUID, or pagination.", "/api/v1/users");
        } else {
            assertThat(result.getResponse().getContentType()).startsWith("application/json");
            jsonPath("$.userType").value(type).match(result);
            if (type.equals("ARK")) jsonPath("$.clientId").value(org.hamcrest.Matchers.nullValue()).match(result);
            else jsonPath("$.clientId").value(CLIENT.toString()).match(result);
        }
        assertThat(clientJdbc.queryForObject("select count(*) from app_users where subject=?", Integer.class, subject))
                .isEqualTo(expectedStatus == 201 ? 1 : 0);
        assertThat(clientJdbc.queryForObject("select count(*) from administrative_audit where new_values->>'subject'=?", Integer.class, subject))
                .isEqualTo(expectedStatus == 201 ? 1 : 0);
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/v1/me,*/*,405,HttpRequestMethodNotSupportedException", "GET,/api/v1/me,application/xml,406,HttpMediaTypeNotAcceptableException", "GET,/api/v1/submission-missing,*/*,404,NoResourceFoundException", "GET,/submission-missing,*/*,404,NoResourceFoundException"})
    void frameworkErrorsPreserveStatusAndHeaders(String method, String path, String accept,
                                                 int frameworkStatus, String exceptionType) throws Exception {
        var result = mvc.perform(auth(request(org.springframework.http.HttpMethod.valueOf(method), path)).accept(accept)).andReturn();
        String title = switch (frameworkStatus) {
            case 405 -> "MethodNotAllowed";
            case 406 -> "NotAcceptable";
            default -> "NotFound";
        };
        String detail = switch (frameworkStatus) {
            case 405 -> "Request method is not supported for this resource.";
            case 406 -> "Requested response media type is not supported.";
            default -> "Resource not found.";
        };
        problem(result, frameworkStatus, title, detail, path);
        assertThat(result.getResolvedException()).isInstanceOf(org.springframework.web.ErrorResponse.class);
        assertThat(result.getResolvedException().getClass().getSimpleName()).isEqualTo(exceptionType);
        var frameworkError = (org.springframework.web.ErrorResponse) result.getResolvedException();
        assertThat(frameworkError.getStatusCode().value()).isEqualTo(frameworkStatus);
        frameworkError.getHeaders().forEach((name, values) ->
                assertThat(result.getResponse().getHeaders(name)).containsExactlyElementsOf(values));
        if (frameworkStatus == 405) assertThat(frameworkError.getHeaders().getAllow())
                .contains(org.springframework.http.HttpMethod.GET);
        if (frameworkStatus == 406) assertThat(result.getResponse().getHeader("Accept"))
                .contains("application/json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me", "/api/v1/submission-missing", "/submission-missing"})
    void unauthenticatedControlRemains401(String path) throws Exception {
        var result = mvc.perform(get(path).accept("application/xml")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        content().contentTypeCompatibleWith("application/problem+json").match(result);
        jsonPath("$.status").value(401).match(result);
        jsonPath("$.detail").value("A valid bearer access token is required").match(result);
    }

    @ParameterizedTest
    @CsvSource({"missing,404,NotFound,Test resource not found", "conflict,409,Conflict,Test state conflict",
            "unexpected,500,InternalError,Unexpected server failure; internal implementation details are not exposed."})
    void domainAndUnexpectedExceptionsKeepExistingResponses(String kind, int status, String title, String detail) throws Exception {
        RuntimeException failure = switch (kind) {
            case "missing" -> new ResourceNotFoundException("Test resource not found");
            case "conflict" -> new StateConflictException("Test state conflict");
            default -> new IllegalStateException("Sensitive diagnostic must not be returned");
        };
        // Inject failure on an existing route without adding a test-only HTTP endpoint.
        doThrow(failure).when(administration).createClient(any());
        var result = mvc.perform(auth(post("/api/v1/clients")).contentType("application/json")
                .content("{\"name\":\"Failure regression\",\"status\":\"ACTIVE\"}")).andReturn();
        problem(result, status, title, detail, "/api/v1/clients");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Sensitive diagnostic", "stackTrace");
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + bearer);
    }

    private void problem(MvcResult result, int expectedStatus, String title, String detail, String path) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        content().contentTypeCompatibleWith("application/problem+json").match(result);
        jsonPath("$.type").value("about:blank").match(result);
        jsonPath("$.title").value(title).match(result);
        jsonPath("$.status").value(expectedStatus).match(result);
        jsonPath("$.detail").value(detail).match(result);
        jsonPath("$.instance").value(path).match(result);
    }
}
