package com.yugabyte.simulation.dao.api;

public class DatabaseConnectionRequest {
    private String host;
    private Integer port;
    private String databaseName;
    private String username;
    private String password;
    private Integer maxPoolSize;
    private Boolean ssl;
    private String sslMode;
    private String sslRootCertId;
    private String sslRootCert;
    private String topologyKeys;

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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Integer getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(Integer maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public Boolean getSsl() {
        return ssl;
    }

    public void setSsl(Boolean ssl) {
        this.ssl = ssl;
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

    public String getSslRootCert() {
        return sslRootCert;
    }

    public void setSslRootCert(String sslRootCert) {
        this.sslRootCert = sslRootCert;
    }

    public String getTopologyKeys() {
        return topologyKeys;
    }

    public void setTopologyKeys(String topologyKeys) {
        this.topologyKeys = topologyKeys;
    }
}
