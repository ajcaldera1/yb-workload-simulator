package com.yugabyte.simulation.dao.api;

public class DatabaseConnectionResponse {
    private int result;
    private String message;
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private boolean sslEnabled;
    private String sslMode;
    private String sslRootCertId;

    public int getResult() {
        return result;
    }

    public void setResult(int result) {
        this.result = result;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean sslEnabled) {
        this.sslEnabled = sslEnabled;
    }

    public String getSslMode() {
        return sslMode;
    }

    public void setSslMode(String sslMode) {
        this.sslMode = sslMode;
    }

    public String getSslRootCertId() {
        return sslRootCertId;
    }

    public void setSslRootCertId(String sslRootCertId) {
        this.sslRootCertId = sslRootCertId;
    }
}
