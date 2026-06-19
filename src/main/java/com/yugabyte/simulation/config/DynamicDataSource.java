package com.yugabyte.simulation.config;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

public class DynamicDataSource implements DataSource {
    private volatile HikariDataSource delegate;

    public DynamicDataSource(HikariDataSource initialDataSource) {
        this.delegate = initialDataSource;
    }

    public synchronized void setDataSource(HikariDataSource newDataSource) {
        HikariDataSource old = this.delegate;
        this.delegate = newDataSource;
        if (old != null) {
            old.close();
        }
    }

    public HikariDataSource getDelegate() {
        return delegate;
    }

    private HikariDataSource current() {
        HikariDataSource ds = delegate;
        if (ds == null) {
            throw new IllegalStateException("DataSource is not configured");
        }
        return ds;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return current().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return current().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return current().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        current().setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        current().setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return current().getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return current().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return current().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return current().isWrapperFor(iface);
    }
}
