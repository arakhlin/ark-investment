package com.ark.arkinvestmentapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL coverage of explicit operator fixtures; no demo data runs through Flyway. */
class DemoFixtureIntegrationTests {
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17-alpine");
    static { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }
    JdbcTemplate jdbc;
    String database;
    String jdbcUrl;
    String provision;
    String financial;

    static String node(String expression) throws Exception {
        var process=new ProcessBuilder("node","-e","process.stdout.write("+expression+")").redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();return output;
    }
    @BeforeEach void freshDatabase() throws Exception {
        database="demo_"+UUID.randomUUID().toString().replace("-","");
        assertThat(POSTGRES.execInContainer("psql","-U",POSTGRES.getUsername(),"-d",POSTGRES.getDatabaseName(),"-c","CREATE DATABASE "+database).getExitCode()).isZero();
        String url=POSTGRES.getJdbcUrl().replace("/"+POSTGRES.getDatabaseName()+"?","/"+database+"?");
        jdbcUrl=url;
        var source=new DriverManagerDataSource(url,POSTGRES.getUsername(),POSTGRES.getPassword());
        jdbc=new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        assertThat(count("clients")).isZero();assertThat(count("transactions")).isZero();
        var support=Files.readString(Path.of("docs/demo/support.sql"));
        provision=support+node("require('./docs/demo/fixtures.cjs').provisionSql('https://issuer.test')");
        financial=support+node("require('./docs/demo/fixtures.cjs').financialSql('https://issuer.test')");
    }
    int count(String table) { return jdbc.queryForObject("select count(*) from "+table,Integer.class); }
    Container.ExecResult run(String script) throws Exception {
        POSTGRES.copyFileToContainer(Transferable.of(script),"/tmp/ark-demo-fixtures.sql");
        return POSTGRES.execInContainer("psql","-X","-q","-U",POSTGRES.getUsername(),"-d",database,"-f","/tmp/ark-demo-fixtures.sql");
    }
    void success(String script) throws Exception {
        var result=run(script);assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    @Test void repeatedProvisionAndSeedPreserveIdentityAuditAndUnrelatedData() throws Exception {
        success(provision);success(provision);
        assertThat(count("clients")).isEqualTo(2);assertThat(count("app_users")).isEqualTo(7);
        var client=jdbc.queryForObject("select id from clients order by id limit 1",UUID.class);
        var unrelated=UUID.randomUUID();jdbc.update("insert into funds(id,client_id,name) values (?,?,'Unrelated')",unrelated,client);
        success(financial);
        var original=jdbc.queryForList("select * from transaction_audit order by id");
        success(financial);success(provision);
        assertThat(count("funds")).isEqualTo(7);assertThat(count("investors")).isEqualTo(10);
        assertThat(count("transactions")).isEqualTo(80);assertThat(count("transaction_audit")).isEqualTo(80);
        assertThat(jdbc.queryForList("select * from transaction_audit order by id")).isEqualTo(original);
        assertThat(jdbc.queryForObject("select name from funds where client_id=? and id=?",String.class,client,unrelated)).isEqualTo("Unrelated");
    }
    @Test void pinnedPublicSchemaSurvivesRoleNamedSchemaOnRestart() throws Exception {
        success(provision);success(financial);
        // PostgreSQL's default "$user",public search path changes current_schema once this exists.
        jdbc.execute("create schema " + POSTGRES.getUsername());
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(POSTGRES.getUsername());
        var pinned=new DriverManagerDataSource(jdbcUrl+"&currentSchema=public",POSTGRES.getUsername(),POSTGRES.getPassword());
        assertThat(new JdbcTemplate(pinned).queryForObject("select current_schema()",String.class)).isEqualTo("public");
        var flyway=Flyway.configure().dataSource(pinned).locations("classpath:db/migration").load();
        flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(new JdbcTemplate(pinned).queryForObject("select count(*) from transactions",Integer.class)).isEqualTo(80);
    }

    @Test void unownedDeterministicIdCollisionIsRejectedWithoutAdoption() throws Exception {
        success(provision);
        String id=node("require('./docs/demo/fixtures.cjs').financial(require('./docs/demo/fixtures.cjs').clients[0]).funds[0].id");
        String client=node("require('./docs/demo/fixtures.cjs').clients[0].id");
        jdbc.update("insert into funds(id,client_id,name) values (?::uuid,?::uuid,'Unrelated collision')",id,client);
        var result=run(financial);assertThat(result.getExitCode()).isNotZero();assertThat(result.getStderr()).contains("Unowned fixture collision");
        assertThat(count("funds")).isEqualTo(1);assertThat(count("transactions")).isZero();
        assertThat(jdbc.queryForObject("select name from funds where id=?::uuid",String.class,id)).isEqualTo("Unrelated collision");
    }
    @Test void changedAndRemovedFixturesAreNotSilentlyOverwrittenOrResurrected() throws Exception {
        success(provision);success(financial);
        String id=node("require('./docs/demo/fixtures.cjs').financial(require('./docs/demo/fixtures.cjs').clients[0]).transactions[0].id");
        jdbc.update("update transactions set amount=99 where id=?::uuid",id);
        assertThat(run(financial).getStderr()).contains("Existing fixture was changed");
        assertThat(jdbc.queryForObject("select amount::text from transactions where id=?::uuid",String.class,id)).isEqualTo("99.00");
        jdbc.update("delete from transactions where id=?::uuid",id);
        assertThat(run(financial).getStderr()).contains("Owned fixture was removed");
        assertThat(count("transactions")).isEqualTo(79);assertThat(count("transaction_audit")).isEqualTo(80);
    }
    @Test void auditFailureRollsBackAllFinancialFixturesAndOwnership() throws Exception {
        success(provision);
        jdbc.execute("alter table transaction_audit add constraint reject_demo_audit check(operation<>'CREATE')");
        assertThat(run(financial).getExitCode()).isNotZero();
        assertThat(count("funds")).isZero();assertThat(count("transactions")).isZero();assertThat(count("transaction_audit")).isZero();
        assertThat(count("ark_demo.fixtures")).isEqualTo(9);
    }
}
