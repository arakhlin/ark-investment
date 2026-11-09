package com.ark.arkinvestmentapi;

import com.ark.arkinvestmentapi.transactions.TransactionInput;
import com.ark.arkinvestmentapi.transactions.TransactionService;
import com.ark.arkinvestmentapi.transactions.TransactionType;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.utility.MountableFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class TransactionAuditIntegrationTests extends PostgresTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionService service;
    @Autowired PlatformTransactionManager transactionManager;
    UUID fund, investor;

    @BeforeEach
    void prepare() {
        fund = UUID.randomUUID();
        investor = UUID.randomUUID();
        jdbc.update("insert into funds(client_id,id,name) values ('00000000-0000-0000-0000-000000000001',?, 'Audit fund')", fund);
        jdbc.update("insert into investors(client_id,id,name,email) values ('00000000-0000-0000-0000-000000000001',?, 'Audit investor', 'audit@example.com')", investor);
        jdbc.update("insert into fund_investors(client_id,id,fund_id,investor_id) values ('00000000-0000-0000-0000-000000000001',?,?,?)", UUID.randomUUID(), fund, investor);
    }

    TransactionInput input(String amount) {
        var input = new TransactionInput();
        input.setFundId(fund.toString());
        input.setInvestorId(investor.toString());
        input.setDate("2026-11-06");
        input.setAmount(amount);
        input.setType(TransactionType.CONTRIBUTION);
        return input;
    }

    int audits() {
        return jdbc.queryForObject("select count(*) from transaction_audit where new_fund_id = ? or previous_fund_id = ?", Integer.class, fund, fund);
    }

    void operate(String operation, UUID id) {
        switch (operation) {
            case "CREATE" -> service.create(CLIENT, input("2.00"));
            case "UPDATE" -> service.replace(CLIENT, id, input("2.00"));
            case "DELETE" -> service.delete(CLIENT, id);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "UPDATE", "DELETE"})
    void outerRollbackUndoesBusinessAndAlreadyWrittenAudit(String operation) {
        var id = operation.equals("CREATE") ? null : service.create(CLIENT, input("1.00")).id();
        int before = audits();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            operate(operation, id);
            assertThat(audits()).isEqualTo(before + 1); // Real SQL insert, visible inside shared transaction.
            status.setRollbackOnly();
        });
        assertThat(audits()).isEqualTo(before);
        assertOriginal(operation, id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CREATE", "UPDATE", "DELETE"})
    void auditInsertFailureRollsBackFlushedBusinessWrite(String operation) {
        var id = operation.equals("CREATE") ? null : service.create(CLIENT, input("1.00")).id();
        int before = audits();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Transactional DDL is rolled back too. NOT VALID leaves prior audit rows untouched.
            // Fixed enum test data only; no caller-controlled SQL interpolation.
            jdbc.execute("alter table transaction_audit add constraint test_reject_audit check (operation <> '" + operation + "') not valid");
            operate(operation, id);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(audits()).isEqualTo(before);
        assertOriginal(operation, id);
    }

    void assertOriginal(String operation, UUID id) {
        if (operation.equals("CREATE")) {
            assertThat(jdbc.queryForObject("select count(*) from transactions where fund_id = ?", Integer.class, fund)).isZero();
        } else {
            assertThat(service.get(CLIENT, id).amount()).isEqualTo("1.00");
        }
    }

    @Test
    void identicalPutStillRecordsSuccessfulOperationWithoutChangingPriorAudit() {
        var id = service.create(CLIENT, input("1.00")).id();
        var original = jdbc.queryForMap("select * from transaction_audit where transaction_id = ?", id);
        service.replace(CLIENT, id, input("1.00"));
        assertThat(audits()).isEqualTo(2);
        assertThat(jdbc.queryForMap("select * from transaction_audit where id = ?", original.get("id"))).isEqualTo(original);
        var update = jdbc.queryForMap("select * from transaction_audit where transaction_id = ? and operation = 'UPDATE'", id);
        assertThat(update.get("previous_amount")).isEqualTo(new BigDecimal("1.00"));
        assertThat(update.get("new_amount")).isEqualTo(new BigDecimal("1.00"));
    }

    @Test
    void trustedBootstrapScriptProvisionsAndRollsBackDisabledIdentity() throws Exception {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(
                Path.of("docs/bootstrap-client.sql").toAbsolutePath()), "/tmp/ark-bootstrap.sql");
        String subject="bootstrap-"+UUID.randomUUID();
        String client=""+UUID.randomUUID();
        var provisioned=POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-v", "client_id="+client, "-v", "client_name=Bootstrap test", "-v", "issuer=https://issuer.test",
                "-v", "subject="+subject, "-f", "/tmp/ark-bootstrap.sql");
        assertThat(provisioned.getExitCode()).as(provisioned.getStderr()).isZero();
        var repeated=POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-v", "client_id="+client, "-v", "client_name=Bootstrap test",
                "-v", "issuer=https://issuer.test", "-v", "subject="+subject, "-f", "/tmp/ark-bootstrap.sql");
        assertThat(repeated.getExitCode()).as(repeated.getStderr()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from clients where id=?",Integer.class,UUID.fromString(client))).isEqualTo(0);
        var id=jdbc.queryForObject("select id from app_users where issuer='https://issuer.test' and subject=?",UUID.class,subject);
        assertThat(jdbc.queryForObject("select count(*) from app_users where id=? and role='ARK_ADMIN' and status='ACTIVE'",Integer.class,id)).isEqualTo(1);
        jdbc.update("update app_users set status='DISABLED' where id=?",id);
        int before=jdbc.queryForObject("select count(*) from clients",Integer.class);
        var rejected=POSTGRES.execInContainer("psql", "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-v", "ON_ERROR_STOP=1", "-v", "client_id="+client, "-v", "client_name=Bootstrap test", "-v", "issuer=https://issuer.test",
                "-v", "subject="+subject, "-f", "/tmp/ark-bootstrap.sql");
        assertThat(rejected.getExitCode()).isNotZero();
        assertThat(jdbc.queryForObject("select count(*) from clients",Integer.class)).isEqualTo(before);
    }

    @Test
    void financialDatabaseValidationScript() throws Exception {
        jdbc.execute(Files.readString(Path.of("docs/database-validation.sql")));
    }

    @Test
    void hardeningUpgradePreservesLegacyUsersWithoutAutomaticallyTrustingThem() {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        var config=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true);
        try {
            assertThat(config.target("1").load().migrate().migrationsExecuted).isEqualTo(1);
            var legacy=UUID.randomUUID();
            jdbc.update("insert into "+schema+".app_users(id,issuer,subject,user_type,role,status) values (?,'https://issuer.test','legacy','ARK','ARK_ADMIN','ACTIVE')",legacy);
            var upgraded=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            upgraded.validate();assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select id from "+schema+".app_users where subject='legacy'",UUID.class)).isEqualTo(legacy);
            assertThat(jdbc.queryForObject("select count(*) from "+schema+".trusted_identities",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from "+schema+".administrative_audit",Integer.class)).isZero();
        } finally {jdbc.execute("drop schema "+schema+" cascade");}
    }

    @Test
    void freshBaselineMigratesAndValidatesIdempotently() {
        String schema = "baseline_" + UUID.randomUUID().toString().replace("-", "");
        var flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(true).load();
        try {
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=? and table_name in ('clients','app_users','funds','investors','fund_investors','transactions','transaction_audit')", Integer.class, schema)).isEqualTo(7);
        } finally {
            jdbc.execute("drop schema " + schema + " cascade");
        }
    }
}
