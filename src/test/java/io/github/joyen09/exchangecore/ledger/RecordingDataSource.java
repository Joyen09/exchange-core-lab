package io.github.joyen09.exchangecore.ledger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Records the SQL each connection prepares, in order.
 *
 * <p>About thirty lines, and a deliberate alternative to adding a JDBC-proxy dependency for one
 * assertion. It exists so {@link LedgerStatementOrderIT} can assert an <em>ordering</em> rather than
 * an outcome — the overdraft race is invisible to outcome-based tests whenever the scheduler happens
 * to be kind.
 */
final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> statements = Collections.synchronizedList(new ArrayList<>());

    RecordingDataSource(DataSource delegate) {
        super(delegate);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    void clear() {
        statements.clear();
    }

    @Override
    public Connection getConnection() throws SQLException {
        return record(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return record(super.getConnection(username, password));
    }

    private Connection record(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (args != null && args.length > 0 && args[0] instanceof String sql
                            && (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))) {
                        statements.add(sql);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
