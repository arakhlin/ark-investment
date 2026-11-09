package com.ark.arkinvestmentapi;

import com.ark.arkinvestmentapi.common.StateConflictException;
import com.ark.arkinvestmentapi.funds.FundInput;
import com.ark.arkinvestmentapi.security.*;
import com.ark.arkinvestmentapi.transactions.*;
import com.ark.arkinvestmentapi.funds.FundService;
import com.ark.arkinvestmentapi.common.ResourceNotFoundException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@SpringBootTest
@AutoConfigureMockMvc
class ClientSecurityIntegrationTests extends PostgresTestSupport {
    @Autowired MockMvc mvc;
    @Autowired AdministrationService administration;
    @Autowired FundService funds;
    @Autowired TransactionService transactions;
    UUID other, manager, viewer, outsider, otherFund, otherInvestor, otherTransaction, fund, investor, transaction;
    String root;

    @BeforeEach
    void prepareClients() {
        // Fresh fixture UUIDs prevent order dependence with the existing financial suites.
        other=UUID.randomUUID(); manager=user("manager"); viewer=user("viewer"); outsider=user("outsider");
        clientJdbc.update("insert into clients(id,name,status) values (?,'Other','ACTIVE')",other);
        membership(CLIENT,manager,"CLIENT_MANAGER"); membership(CLIENT,viewer,"CLIENT_REPORT_VIEWER");

        var a=ledger(CLIENT,"10.00"); fund=a[0]; investor=a[1]; transaction=a[2];
        var b=ledger(other,"900.00"); otherFund=b[0]; otherInvestor=b[1]; otherTransaction=b[2];
        root="/api/v1/clients/"+CLIENT;
    }
    UUID user(String label) {
        var id=UUID.randomUUID();
        clientJdbc.update("insert into app_users(id,issuer,subject,status,user_type,client_id,role) values (?,'https://issuer.test',?,'ACTIVE','CLIENT',?,'CLIENT_REPORT_VIEWER')",id,id.toString(),CLIENT);
        return id;
    }
    void membership(UUID client, UUID user, String role) {
        clientJdbc.update("update app_users set client_id=?,role=?,status='ACTIVE' where id=?",client,role,user);
        clientJdbc.update("insert into trusted_identities(issuer,subject,user_type,client_id) values ('https://issuer.test',?,'CLIENT',?) on conflict(issuer,subject) do update set client_id=excluded.client_id",user.toString(),client);
    }
    UUID[] ledger(UUID client, String amount) {
        UUID f=UUID.randomUUID(),i=UUID.randomUUID(),t=UUID.randomUUID();
        clientJdbc.update("insert into funds(client_id,id,name) values (?,?,'Scoped fund')",client,f);
        clientJdbc.update("insert into investors(client_id,id,name,email) values (?,?,'Scoped investor','a@example.com')",client,i);
        clientJdbc.update("insert into fund_investors(client_id,id,fund_id,investor_id) values (?,?,?,?)",client,UUID.randomUUID(),f,i);
        clientJdbc.update("insert into transactions(client_id,id,fund_id,investor_id,transaction_date,amount,type) values (?,?,?,?,date '2026-01-01',?::numeric,'CONTRIBUTION')",client,t,f,i,amount);
        return new UUID[]{f,i,t};
    }
    MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UUID id) {
        return b.with(jwt().jwt(j->j.issuer("https://issuer.test").subject(id.equals(ADMIN)?"test-admin":id.toString())));
    }
    String body(UUID f,UUID i) {
        return "{\"fundId\":\""+f+"\",\"investorId\":\""+i+"\",\"date\":\"2026-01-01\",\"amount\":\"5.00\",\"type\":\"CONTRIBUTION\"}";
    }
    void identity(UUID user) {
        var token=Jwt.withTokenValue("test").header("alg","RS256").issuer("https://issuer.test")
                .subject(user.equals(ADMIN)?"test-admin":user.toString()).build();
        var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new JwtAuthenticationToken(token, List.of()));
        SecurityContextHolder.setContext(context);
    }

    @Test
    void actualSignedJwtValidation() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer malformed")).andExpect(status().isUnauthorized());
        var future=Instant.now().plusSeconds(300);
        for (String invalid : List.of(
                TestTokens.token("test-admin","https://issuer.test","ark-api",future,true),
                TestTokens.token("test-admin","https://foreign.test","ark-api",future,false),
                TestTokens.token("test-admin","https://issuer.test","other-api",future,false),
                TestTokens.token("test-admin","https://issuer.test",null,future,false),
                TestTokens.token(null,"https://issuer.test","ark-api",future,false),
                TestTokens.token("test-admin","https://issuer.test","ark-api",Instant.now().minusSeconds(300),false),
                TestTokens.token("test-admin","https://issuer.test","ark-api",null,false))) {
            mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+invalid))
                    .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.type").value("about:blank")).andExpect(jsonPath("$.detail").isString());
        }
        String valid=TestTokens.token("test-admin","https://issuer.test","ark-api",future,false);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+valid)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(ADMIN.toString()));
        clientJdbc.update("update app_users set status='DISABLED' where id=?",ADMIN);
        mvc.perform(get("/api/v1/me").header("Authorization","Bearer "+valid)).andExpect(status().isUnauthorized());
    }

    @Test
    void clientVisibility() throws Exception {
        mvc.perform(as(get("/api/v1/clients/"+other+"/funds"),ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/me/clients"),manager)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        clientJdbc.update("update clients set status='SUSPENDED' where id=?",CLIENT);
        mvc.perform(as(get(root+"/funds"),ADMIN)).andExpect(status().isNotFound());
    }

    @Test
    void crossClientObjectsReadsWritesReportsAndCounts() throws Exception {
        for (var user:List.of(ADMIN,manager)) {
            mvc.perform(as(get(root+"/funds/"+otherFund),user)).andExpect(status().isNotFound());
            mvc.perform(as(get(root+"/investors/"+otherInvestor),user)).andExpect(status().isNotFound());
            mvc.perform(as(get(root+"/transactions/"+otherTransaction),user)).andExpect(status().isNotFound());
            mvc.perform(as(put(root+"/transactions/"+otherTransaction).contentType("application/json").content(body(otherFund,otherInvestor)),user)).andExpect(status().isNotFound());
            mvc.perform(as(post(root+"/transactions").contentType("application/json").content(body(fund,otherInvestor)),user)).andExpect(status().isNotFound());
        }
        mvc.perform(as(delete(root+"/transactions/"+otherTransaction),ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(put(root+"/funds/"+fund+"/investors/"+otherInvestor),ADMIN)).andExpect(status().isNotFound());
        for (var user:List.of(ADMIN,manager,viewer)) {
            mvc.perform(as(get(root+"/funds/"+otherFund+"/report"),user)).andExpect(status().isNotFound());
            mvc.perform(as(get(root+"/investors/"+investor+"/report").param("fundId",otherFund.toString()),user)).andExpect(status().isNotFound());
            mvc.perform(as(get(root+"/funds/"+fund+"/report"),user)).andExpect(status().isOk()).andExpect(jsonPath("$.totals.closingBalance").value("10.00"));
        }
        var expected=clientJdbc.queryForObject("select count(*) from transactions where client_id=?",Integer.class,CLIENT);
        mvc.perform(as(get(root+"/transactions").param("size","1"),manager)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(expected));
        var fundCount=clientJdbc.queryForObject("select count(*) from funds where client_id=?",Integer.class,CLIENT);
        mvc.perform(as(get(root+"/funds").param("size","1"),manager)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(fundCount));
        var investorCount=clientJdbc.queryForObject("select count(*) from investors where client_id=?",Integer.class,CLIENT);
        mvc.perform(as(get(root+"/investors").param("size","1"),manager)).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(investorCount));
    }

    @Test
    void roleMatrixAndEscalation() throws Exception {
        for(String role:List.of("CLIENT_REPORT_VIEWER","ARK_REPORT_VIEWER","ARK_ADMIN")) {
            var id=role.startsWith("ARK")?ark(role):viewer;
            mvc.perform(as(post(root+"/transactions").contentType("application/json").content(body(fund,investor)),id)).andExpect(status().isForbidden());
            mvc.perform(as(delete(root+"/transactions/"+transaction),id)).andExpect(status().isForbidden());
            mvc.perform(as(get(root+"/funds/"+fund+"/report"),id)).andExpect(role.equals("ARK_ADMIN")?status().isForbidden():status().isOk());
        }
        for(var id:List.of(ADMIN,manager,ark("ARK_OPERATIONS"))) {
            mvc.perform(as(post(root+"/transactions").contentType("application/json").content(body(fund,investor)),id)).andExpect(status().isCreated());
            mvc.perform(as(post(root+"/funds").contentType("application/json").content("{\"name\":\"Allowed\"}"),id)).andExpect(status().isCreated());
        }
        for(String role:List.of("ARK_REPORT_VIEWER","ARK_OPERATIONS")) {
            var id=ark(role);
            mvc.perform(as(get("/api/v1/clients/"+other+"/funds/"+otherFund+"/report"),id)).andExpect(status().isOk());
            if(role.equals("ARK_OPERATIONS")) mvc.perform(as(post("/api/v1/clients/"+other+"/transactions").contentType("application/json").content(body(otherFund,otherInvestor)),id)).andExpect(status().isCreated());
        }
        mvc.perform(as(put(root+"/users/"+ADMIN).contentType("application/json").content("{\"role\":\"ARK_ADMIN\",\"status\":\"ACTIVE\"}"),ADMIN)).andExpect(status().isBadRequest());
        mvc.perform(as(post(root+"/transactions").contentType("application/json").content(body(fund,investor).replace("}",",\"clientId\":\""+other+"\"}")),ADMIN)).andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/v1/clients").contentType("application/json").content("{\"name\":\"New\",\"status\":\"ACTIVE\"}"),ADMIN)).andExpect(status().isForbidden());
        var ark=ark("ARK_ADMIN");
        mvc.perform(as(post("/api/v1/clients").contentType("application/json").content("{\"name\":\"New\",\"status\":\"ACTIVE\"}"),ark)).andExpect(status().isCreated());
        mvc.perform(as(get("/api/v1/users"),ark)).andExpect(status().isOk());
        mvc.perform(as(put(root+"/users/"+ADMIN).contentType("application/json").content("{\"role\":\"CLIENT_MANAGER\",\"status\":\"ACTIVE\"}"),ADMIN)).andExpect(status().isConflict());
    }
    UUID ark(String role) {
        var id=UUID.randomUUID();
        clientJdbc.update("insert into app_users(id,issuer,subject,user_type,role,status) values (?,'https://issuer.test',?,'ARK',?,'ACTIVE')",id,id.toString(),role);
        trust(id.toString(),UserType.ARK,null);
        return id;
    }

    void trust(String subject, UserType type, UUID client) {
        clientJdbc.update("insert into trusted_identities(issuer,subject,user_type,client_id) values ('https://issuer.test',?,?,?) on conflict do nothing",subject,type.name(),client);
    }

    @Test
    void provisioningChecksOwnershipAndCompatibleRoles() throws Exception {
        var ark=ark("ARK_ADMIN");
        String created=mvc.perform(as(post("/api/v1/clients").contentType("application/json")
                .content("{\"name\":\"Provisioned Client\",\"status\":\"ACTIVE\"}"),ark))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID target=UUID.fromString(new ObjectMapper().readTree(created).get("id").asString());
        String subject=UUID.randomUUID().toString();
        trust(subject,UserType.CLIENT,target);
        String input="{\"issuer\":\"https://issuer.test\",\"subject\":\""+subject+"\",\"userType\":\"CLIENT\",\"clientId\":\""+target+"\",\"role\":\"CLIENT_ADMIN\",\"status\":\"ACTIVE\"}";
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input),ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input),ark)).andExpect(status().isCreated());
        String local=input.replace(target.toString(),CLIENT.toString()).replace("CLIENT_ADMIN","CLIENT_MANAGER").replace("subject\":\"","subject\":\"local-");
        trust("local-"+subject,UserType.CLIENT,CLIENT);
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(local),ADMIN)).andExpect(status().isCreated());
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(local.replace("CLIENT_MANAGER","ARK_ADMIN")),ADMIN)).andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content("{\"issuer\":\"https://issuer.test\",\"subject\":\"escalation\",\"userType\":\"ARK\",\"role\":\"ARK_ADMIN\",\"status\":\"ACTIVE\"}"),ADMIN)).andExpect(status().isForbidden());
        mvc.perform(as(put(root+"/users/"+ADMIN).contentType("application/json").content("{\"role\":\"CLIENT_ADMIN\",\"status\":\"DISABLED\"}"),ark)).andExpect(status().isConflict());
    }

    @Test
    void lastArkAdministratorCannotDisableOrDemoteSelfOrRelyOnUnverifiedUsers() throws Exception {
        clientJdbc.update("update app_users set status='DISABLED' where user_type='ARK'");
        var admin=ark("ARK_ADMIN");
        var fake=ark("ARK_ADMIN");
        clientJdbc.update("delete from trusted_identities where subject=?",fake.toString());
        for(String change:List.of("{\"role\":\"ARK_OPERATIONS\",\"status\":\"ACTIVE\"}",
                "{\"role\":\"ARK_ADMIN\",\"status\":\"DISABLED\"}"))
            mvc.perform(as(put("/api/v1/users/"+admin).contentType("application/json").content(change),admin)).andExpect(status().isConflict());
        mvc.perform(as(put("/api/v1/users/"+fake).contentType("application/json")
                .content("{\"role\":\"ARK_ADMIN\",\"status\":\"ACTIVE\"}"),admin)).andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings={"demote", "disable", "mixed"})
    void concurrentArkDemotionAndSuspensionPreserveVerifiedAdministrator(String mode) throws Exception {
        clientJdbc.update("update app_users set status='DISABLED' where user_type='ARK'");
        var first=ark("ARK_ADMIN");var second=ark("ARK_ADMIN");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Integer>>();
            for(var id:List.of(first,second)) results.add(pool.submit(()->{
                identity(id);start.await();
                try {
                    administration.changeUser(null,id,new AdministrationService.UserChange(
                        (mode.equals("demote") || mode.equals("mixed") && id.equals(first))?ClientRole.ARK_OPERATIONS:ClientRole.ARK_ADMIN,
                        (mode.equals("disable") || mode.equals("mixed") && id.equals(second))?AdministrationService.UserStatus.DISABLED:AdministrationService.UserStatus.ACTIVE));
                    return 204;
                } catch(StateConflictException e) {return 409;}
                finally {SecurityContextHolder.clearContext();}
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,409);
        }
        assertThat(clientJdbc.queryForObject("select count(*) from app_users where role='ARK_ADMIN' and status='ACTIVE'",Integer.class)).isEqualTo(1);
    }

    @Test
    void suspendedClientAdministrationDoesNotEnableFinancialAccess() throws Exception {
        var admin=ark("ARK_ADMIN");var operations=ark("ARK_OPERATIONS");
        clientJdbc.update("update clients set status='SUSPENDED' where id=?",CLIENT);
        mvc.perform(as(get(root+"/users"),admin)).andExpect(status().isOk());
        var subject=UUID.randomUUID().toString();trust(subject,UserType.CLIENT,CLIENT);
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(
            "{\"issuer\":\"https://issuer.test\",\"subject\":\""+subject+"\",\"userType\":\"CLIENT\",\"clientId\":\""+CLIENT+"\",\"role\":\"CLIENT_MANAGER\",\"status\":\"ACTIVE\"}"),admin)).andExpect(status().isCreated());
        mvc.perform(as(put(root+"/users/"+manager).contentType("application/json")
                .content("{\"role\":\"CLIENT_MANAGER\",\"status\":\"DISABLED\"}"),admin)).andExpect(status().isNoContent());
        for(var id:List.of(ADMIN,viewer,operations,admin)) {
            mvc.perform(as(get(root+"/funds"),id)).andExpect(status().isNotFound());
            mvc.perform(as(get(root+"/funds/"+fund+"/report"),id)).andExpect(status().isNotFound());
        }
        mvc.perform(as(get(root+"/users"),ADMIN)).andExpect(status().isNotFound());
        mvc.perform(as(get(root+"/users"),operations)).andExpect(status().isNotFound());
    }

    @Test
    void provisioningRejectsUnverifiedForeignAndDuplicateIdentitiesUniformly() throws Exception {
        var admin=ark("ARK_ADMIN");
        String subject=UUID.randomUUID().toString();
        String input="{\"issuer\":\"https://issuer.test\",\"subject\":\""+subject+"\",\"userType\":\"CLIENT\",\"clientId\":\""+CLIENT+"\",\"role\":\"CLIENT_MANAGER\",\"status\":\"ACTIVE\"}";
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input),admin))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Identity binding unavailable"));
        trust(subject,UserType.CLIENT,CLIENT);
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input.replace("https://issuer.test","https://foreign.test")),admin)).andExpect(status().isConflict());
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input),admin)).andExpect(status().isCreated());
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input),admin))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Identity binding unavailable"));
        mvc.perform(as(post("/api/v1/users").contentType("application/json").content(input.replace(CLIENT.toString(),other.toString())),admin))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("Identity binding unavailable"));
    }

    @Test
    void administrativeAuditSnapshotsAndFailuresAreAtomic() {
        var admin=ark("ARK_ADMIN");identity(admin);
        var client=administration.createClient(new AdministrationService.ClientChange("Audit",AdministrationService.ClientStatus.ACTIVE));
        administration.replaceClient(client.id(),new AdministrationService.ClientChange("Suspended",AdministrationService.ClientStatus.SUSPENDED));
        var subject=UUID.randomUUID().toString();trust(subject,UserType.CLIENT,client.id());
        var user=administration.createUser(new AdministrationService.NewUser("https://issuer.test",subject,UserType.CLIENT,client.id(),ClientRole.CLIENT_MANAGER,AdministrationService.UserStatus.ACTIVE));
        administration.changeUser(client.id(),user.id(),new AdministrationService.UserChange(ClientRole.CLIENT_REPORT_VIEWER,AdministrationService.UserStatus.DISABLED));
        var rows=clientJdbc.queryForList("select * from administrative_audit where target_id in (?,?) order by occurred_at",client.id(),user.id());
        assertThat(rows).hasSize(4);
        for(var row:rows) {assertThat(row.get("actor_user_id")).isEqualTo(admin);assertThat(row.get("occurred_at")).isNotNull();assertThat(row.get("new_values")).isNotNull();}
        assertThat(rows.get(1).get("previous_values").toString()).contains("ACTIVE","Audit");
        assertThat(rows.get(3).get("new_values").toString()).contains("DISABLED","CLIENT_REPORT_VIEWER");
        clientJdbc.execute("alter table administrative_audit add constraint reject_test_actor check (actor_user_id <> '"+admin+"') not valid");
        try {
            assertThatThrownBy(()->administration.replaceClient(client.id(),new AdministrationService.ClientChange("Rollback",AdministrationService.ClientStatus.ACTIVE))).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->administration.createClient(new AdministrationService.ClientChange("Rollback create",AdministrationService.ClientStatus.ACTIVE))).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(()->administration.changeUser(client.id(),user.id(),new AdministrationService.UserChange(ClientRole.CLIENT_MANAGER,AdministrationService.UserStatus.ACTIVE))).isInstanceOf(DataIntegrityViolationException.class);
            var next=UUID.randomUUID().toString();trust(next,UserType.CLIENT,client.id());
            assertThatThrownBy(()->administration.createUser(new AdministrationService.NewUser("https://issuer.test",next,UserType.CLIENT,client.id(),ClientRole.CLIENT_MANAGER,AdministrationService.UserStatus.ACTIVE))).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(clientJdbc.queryForObject("select count(*) from app_users where subject=?",Integer.class,next)).isZero();
            assertThat(clientJdbc.queryForObject("select count(*) from clients where name='Rollback create'",Integer.class)).isZero();
            assertThat(clientJdbc.queryForObject("select name from clients where id=?",String.class,client.id())).isEqualTo("Suspended");
            assertThat(clientJdbc.queryForObject("select status from app_users where id=?",String.class,user.id())).isEqualTo("DISABLED");
        } finally {clientJdbc.execute("alter table administrative_audit drop constraint reject_test_actor");}
    }

    @Test
    void databaseEnforcesUserOwnershipAndRoleCompatibility() {
        for(String values:List.of("'CLIENT',null,'CLIENT_ADMIN'", "'ARK','"+CLIENT+"','ARK_ADMIN'", "'CLIENT','"+CLIENT+"','ARK_ADMIN'", "'ARK',null,'CLIENT_MANAGER'", "'CLIENT','"+UUID.randomUUID()+"','CLIENT_ADMIN'")) {
            assertThatThrownBy(()->clientJdbc.update("insert into app_users(id,issuer,subject,user_type,client_id,role,status) values (?,'https://issuer.test',?,"+values+",'ACTIVE')",UUID.randomUUID(),UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(()->clientJdbc.update("insert into app_users(id,issuer,subject,user_type,client_id,role,status) values (?,'https://issuer.test','test-admin','CLIENT',?,'CLIENT_ADMIN','ACTIVE')",UUID.randomUUID(),CLIENT)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentAdministratorDemotionsPreserveOneAdministrator() throws Exception {
        var client=UUID.randomUUID();
        clientJdbc.update("insert into clients(id,name,status) values (?,'Concurrency','ACTIVE')",client);
        var first=user("first");
        var second=user("second");
        membership(client,first,"CLIENT_ADMIN");membership(client,second,"CLIENT_ADMIN");
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<Future<Integer>>();
            for(var id:List.of(first,second)) results.add(pool.submit(()->{
                identity(id);start.await();
                try {
                    administration.changeUser(client,id,new AdministrationService.UserChange(ClientRole.CLIENT_REPORT_VIEWER,AdministrationService.UserStatus.ACTIVE));return 204;
                } catch(StateConflictException e) { return 409; }
                finally { SecurityContextHolder.clearContext(); }
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,409);
        }
    }

    @Test
    void generatedEntityMutationsIncludeClientPredicate() {
        identity(ADMIN);
        TestSqlCapture.SQL.set(new ArrayList<>());
        try {
            var emptyFund=funds.create(CLIENT,new FundInput("SQL scope",null));
            funds.replace(CLIENT,emptyFund.id(),new FundInput("Updated",null));
            funds.delete(CLIENT,emptyFund.id());
            var input=new TransactionInput();
            input.setFundId(fund.toString()); input.setInvestorId(investor.toString());
            input.setAmount("2.00"); input.setDate("2026-01-01"); input.setType(TransactionType.CONTRIBUTION);
            transactions.replace(CLIENT,transaction,input);
            transactions.delete(CLIENT,transaction);
            for (var sql : TestSqlCapture.SQL.get()) {
                if (sql.startsWith("select ") && (sql.contains(" from funds ") || sql.contains(" from transactions ")))
                    assertThat(sql.substring(sql.indexOf(" where "))).contains("client_id");
            }
            var writes=TestSqlCapture.SQL.get().stream().filter(sql -> sql.startsWith("update ") || sql.startsWith("delete ")).toList();
            assertThat(writes).hasSize(4);
            for(var sql:writes) assertThat(sql.substring(sql.indexOf(" where "))).contains("client_id");
        } finally { TestSqlCapture.SQL.remove(); }
    }

    @Test
    void foreignRowsAreNotAcquiredByClientScopedLockQueries() throws Exception {
        try (var connection=Objects.requireNonNull(clientJdbc.getDataSource()).getConnection();
             var pool=Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try {
                for (String table : List.of("transactions", "funds")) {
                    try (var statement=connection.prepareStatement("select id from " + table + " where client_id=? and id=? for update")) {
                        statement.setObject(1,other);
                        statement.setObject(2,table.equals("transactions")?otherTransaction:otherFund);
                        try(var rows=statement.executeQuery()) { assertThat(rows.next()).isTrue(); }
                    }
                }
                var result=pool.submit(()-> {
                    identity(ADMIN);
                    try {
                        assertThatThrownBy(()->transactions.delete(CLIENT,otherTransaction)).isInstanceOf(ResourceNotFoundException.class);
                        assertThatThrownBy(()->funds.lock(CLIENT,otherFund)).isInstanceOf(ResourceNotFoundException.class);
                        return true;
                    } finally { SecurityContextHolder.clearContext(); }
                });
                // These must finish while the foreign rows are still locked by another transaction.
                assertThat(result.get(5,TimeUnit.SECONDS)).isTrue();
            } finally { connection.rollback(); }
        }
    }

    @Test
    void databaseRejectsCrossClientRelationshipsAndAuditRetainsActor() throws Exception {
        assertThatThrownBy(()->clientJdbc.update("insert into fund_investors(client_id,id,fund_id,investor_id) values (?,?,?,?)",CLIENT,UUID.randomUUID(),fund,otherInvestor)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->clientJdbc.update("insert into transactions(client_id,id,fund_id,investor_id,transaction_date,amount,type) values (?,?,?,?,current_date,1,'CONTRIBUTION')",CLIENT,UUID.randomUUID(),otherFund,otherInvestor)).isInstanceOf(DataIntegrityViolationException.class);
        mvc.perform(as(delete(root+"/transactions/"+transaction),ADMIN)).andExpect(status().isNoContent());
        var audit=clientJdbc.queryForMap("select * from transaction_audit where client_id=? and transaction_id=?",CLIENT,transaction);
        assertThat(audit.get("actor_user_id")).isEqualTo(ADMIN);
        assertThat(audit.get("actor_issuer")).isEqualTo("https://issuer.test");
        assertThat(audit.get("actor_subject")).isEqualTo("test-admin");
        assertThat(audit.get("operation")).isEqualTo("DELETE");
        assertThat(clientJdbc.queryForObject("select count(*) from transactions where client_id=? and id=?",Integer.class,other,otherTransaction)).isEqualTo(1);
    }
}
