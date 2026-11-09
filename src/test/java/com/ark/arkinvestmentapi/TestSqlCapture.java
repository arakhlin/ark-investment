package com.ark.arkinvestmentapi;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import java.util.List;

/** Opt-in capture on the invoking test thread; never installed in application configuration. */
public class TestSqlCapture implements StatementInspector {
    static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();
    @Override
    public String inspect(String sql) {
        var statements=SQL.get();
        if(statements!=null) statements.add(sql);
        return sql;
    }
}
