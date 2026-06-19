package com.yugabyte.simulation.service;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.yugabyte.simulation.config.DynamicDataSource;
import com.yugabyte.simulation.dao.api.DatabaseConnectionRequest;
import com.yugabyte.simulation.dao.api.DatabaseConnectionResponse;
import com.yugabyte.simulation.exception.ConflictException;
import com.yugabyte.simulation.exception.ParameterValidationException;
import com.yugabyte.simulation.workload.WorkloadManager;
import com.yugabyte.simulation.workload.WorkloadTypeInstance;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

@Service
public class DatabaseConnectionService {
    private static final String PG_DRIVER_PROFILE = "pgdriver";

    @Autowired
    private DynamicDataSource dynamicDataSource;

    @Autowired
    private HikariConfig baseHikariConfig;

    @Autowired
    private CertificateStorageService certificateStorageService;

    @Autowired
    private WorkloadManager workloadManager;

    @Autowired
    private Environment environment;

    private volatile DatabaseConnectionResponse currentConfig;

    public DatabaseConnectionResponse reconfigure(DatabaseConnectionRequest request) throws Exception {
        ensureNoActiveWorkloads();
        validateRequest(request);

        String sslRootCertId = resolveSslRootCertId(request);
        String sslRootCertPath = resolveSslRootCertPath(request, sslRootCertId);

        boolean sslEnabled = Boolean.TRUE.equals(request.getSsl());
        String sslMode = sslEnabled ? normalizeSslMode(request.getSslMode()) : "disable";

        HikariConfig config = copyBaseConfig();
        config.setUsername(firstNonNull(request.getUsername(), baseHikariConfig.getUsername()));
        config.setPassword(firstNonNull(request.getPassword(), baseHikariConfig.getPassword()));
        if (request.getMaxPoolSize() != null) {
            config.setMaximumPoolSize(request.getMaxPoolSize());
        }

        String host = firstNonNull(request.getHost(), getDefaultHost());
        int port = request.getPort() != null ? request.getPort() : getDefaultPort();
        String databaseName = firstNonNull(request.getDatabaseName(), getDefaultDatabaseName());

        if (usesPgDriverProfile()) {
            configurePgDriver(config, host, port, databaseName, sslEnabled, sslMode, sslRootCertPath);
        } else {
            configureYbDriver(config, host, port, databaseName, sslEnabled, sslMode, sslRootCertPath, request.getTopologyKeys());
        }

        HikariDataSource newDataSource = new HikariDataSource(config);
        try (Connection connection = newDataSource.getConnection()) {
            connection.isValid(5);
        }

        dynamicDataSource.setDataSource(newDataSource);
        currentConfig = buildResponse(host, port, databaseName, config.getUsername(), sslEnabled, sslMode, sslRootCertId);
        currentConfig.setResult(0);
        currentConfig.setMessage("Connection configured");
        return currentConfig;
    }

    public DatabaseConnectionResponse getCurrentConfiguration() {
        if (currentConfig != null) {
            return currentConfig;
        }
        DatabaseConnectionResponse response = new DatabaseConnectionResponse();
        response.setResult(0);
        response.setMessage("Using startup configuration");
        response.setHost(getDefaultHost());
        response.setPort(getDefaultPort());
        response.setDatabaseName(getDefaultDatabaseName());
        response.setUsername(baseHikariConfig.getUsername());
        response.setSslEnabled(isStartupSslEnabled());
        response.setSslMode(getStartupSslMode());
        return response;
    }

    private void ensureNoActiveWorkloads() {
        for (WorkloadTypeInstance instance : workloadManager.getActiveWorkloads()) {
            if (instance.getType().canBeTerminated() && !instance.isComplete() && !instance.isTerminated()) {
                throw new ConflictException("Cannot reconfigure database connection while workloads are running");
            }
        }
    }

    private void validateRequest(DatabaseConnectionRequest request) {
        if (request.getHost() == null && getDefaultHost() == null) {
            throw new ParameterValidationException("host is required");
        }
        if (Boolean.TRUE.equals(request.getSsl())) {
            String mode = normalizeSslMode(request.getSslMode());
            if ("verify-ca".equals(mode) || "verify-full".equals(mode)) {
                boolean hasCert = hasText(request.getSslRootCertId()) || hasText(request.getSslRootCert());
                if (!hasCert) {
                    throw new ParameterValidationException("sslRootCertId or sslRootCert is required when SSL verification is enabled");
                }
            }
        }
    }

    private String resolveSslRootCertId(DatabaseConnectionRequest request) throws Exception {
        if (request.getSslRootCertId() != null && !request.getSslRootCertId().trim().isEmpty()) {
            return request.getSslRootCertId().trim();
        }
        if (request.getSslRootCert() != null && !request.getSslRootCert().trim().isEmpty()) {
            String generatedId = "inline-" + System.currentTimeMillis();
            certificateStorageService.store(generatedId, request.getSslRootCert());
            return generatedId;
        }
        return null;
    }

    private String resolveSslRootCertPath(DatabaseConnectionRequest request, String sslRootCertId) throws Exception {
        if (sslRootCertId != null) {
            return certificateStorageService.getCertificatePath(sslRootCertId);
        }
        if (request.getSslRootCert() != null && !request.getSslRootCert().trim().isEmpty()) {
            return certificateStorageService.getCertificatePath(resolveSslRootCertId(request));
        }
        return null;
    }

    private HikariConfig copyBaseConfig() {
        HikariConfig config = new HikariConfig();
        config.setConnectionInitSql(baseHikariConfig.getConnectionInitSql());
        config.setMaximumPoolSize(baseHikariConfig.getMaximumPoolSize());
        config.setMaxLifetime(baseHikariConfig.getMaxLifetime());
        config.setUsername(baseHikariConfig.getUsername());
        config.setPassword(baseHikariConfig.getPassword());
        config.setDataSourceClassName(baseHikariConfig.getDataSourceClassName());
        config.setDriverClassName(baseHikariConfig.getDriverClassName());
        config.setJdbcUrl(baseHikariConfig.getJdbcUrl());
        Properties props = baseHikariConfig.getDataSourceProperties();
        if (props != null) {
            Properties copy = new Properties();
            copy.putAll(props);
            config.setDataSourceProperties(copy);
        }
        return config;
    }

    private void configurePgDriver(HikariConfig config, String host, int port, String databaseName,
            boolean sslEnabled, String sslMode, String sslRootCertPath) {
        config.setDriverClassName("org.postgresql.Driver");
        config.setDataSourceClassName(null);
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(host).append(':').append(port).append('/').append(databaseName);
        if (sslEnabled) {
            url.append("?ssl=true&sslmode=").append(sslMode);
            if (sslRootCertPath != null) {
                url.append("&sslrootcert=").append(sslRootCertPath);
            }
        }
        config.setJdbcUrl(url.toString());
        config.setDataSourceProperties(new Properties());
    }

    private void configureYbDriver(HikariConfig config, String host, int port, String databaseName,
            boolean sslEnabled, String sslMode, String sslRootCertPath, String topologyKeys) {
        config.setJdbcUrl(null);
        config.setDriverClassName(null);
        config.setDataSourceClassName("com.yugabyte.ysql.YBClusterAwareDataSource");
        Properties props = new Properties();
        props.setProperty("serverName", host);
        props.setProperty("portNumber", Integer.toString(port));
        props.setProperty("databaseName", databaseName);
        props.setProperty("loadBalance", "true");
        props.setProperty("topologyKeys", firstNonNull(topologyKeys, getDefaultTopologyKeys()));
        if (sslEnabled) {
            props.setProperty("ssl", "true");
            props.setProperty("sslmode", sslMode);
            if (sslRootCertPath != null) {
                props.setProperty("sslrootcert", sslRootCertPath);
            }
        }
        config.setDataSourceProperties(props);
    }

    private boolean usesPgDriverProfile() {
        return Arrays.asList(environment.getActiveProfiles()).contains(PG_DRIVER_PROFILE);
    }

    private String getDefaultHost() {
        Properties props = baseHikariConfig.getDataSourceProperties();
        if (props != null && props.getProperty("serverName") != null) {
            return props.getProperty("serverName");
        }
        String jdbcUrl = baseHikariConfig.getJdbcUrl();
        if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:postgresql://")) {
            String remainder = jdbcUrl.substring("jdbc:postgresql://".length());
            int slash = remainder.indexOf('/');
            String hostPort = slash >= 0 ? remainder.substring(0, slash) : remainder;
            int colon = hostPort.indexOf(':');
            return colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        }
        return System.getProperty("node", "127.0.0.1");
    }

    private int getDefaultPort() {
        Properties props = baseHikariConfig.getDataSourceProperties();
        if (props != null && props.getProperty("portNumber") != null) {
            return Integer.parseInt(props.getProperty("portNumber"));
        }
        return Integer.parseInt(System.getProperty("port", "5433"));
    }

    private String getDefaultDatabaseName() {
        Properties props = baseHikariConfig.getDataSourceProperties();
        if (props != null && props.getProperty("databaseName") != null) {
            return props.getProperty("databaseName");
        }
        return System.getProperty("dbname", "yugabyte");
    }

    private String getDefaultTopologyKeys() {
        Properties props = baseHikariConfig.getDataSourceProperties();
        if (props != null && props.getProperty("topologyKeys") != null) {
            return props.getProperty("topologyKeys");
        }
        return "aws.us-west-2.*";
    }

    private boolean isStartupSslEnabled() {
        return Boolean.parseBoolean(System.getProperty("ssl", "false"));
    }

    private String getStartupSslMode() {
        return System.getProperty("sslmode", "disable");
    }

    private static String normalizeSslMode(String sslMode) {
        if (sslMode == null || sslMode.trim().isEmpty()) {
            return "verify-full";
        }
        return sslMode.trim();
    }

    private static DatabaseConnectionResponse buildResponse(String host, int port, String databaseName, String username,
            boolean sslEnabled, String sslMode, String sslRootCertId) {
        DatabaseConnectionResponse response = new DatabaseConnectionResponse();
        response.setHost(host);
        response.setPort(port);
        response.setDatabaseName(databaseName);
        response.setUsername(username);
        response.setSslEnabled(sslEnabled);
        response.setSslMode(sslMode);
        response.setSslRootCertId(sslRootCertId);
        return response;
    }

    private static String firstNonNull(String value, String fallback) {
        return value != null && !value.trim().isEmpty() ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
