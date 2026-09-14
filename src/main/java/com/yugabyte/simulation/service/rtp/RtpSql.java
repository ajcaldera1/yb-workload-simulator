package com.yugabyte.simulation.service.rtp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * CTE write paths from the rtp_demo generator: one statement inserts the full
 * payment graph (or a seed chunk) so related rows commit in a single round trip.
 */
public final class RtpSql {

    public static final String BATCH_INSERT_SQL = load("scripts/rtp-batch-insert.sql");
    public static final String SEED_PARTY_ACCOUNT_SQL = load("scripts/rtp-seed-party-account.sql");
    public static final String SEED_PARTY_ADDRESS_SQL = load("scripts/rtp-seed-party-address.sql");
    public static final String INSERT_LEGAL_ENTITIES_SQL = load("scripts/rtp-insert-legal-entities.sql");
    public static final String INSERT_CLEARING_ACCOUNT_SQL = load("scripts/rtp-insert-clearing-account.sql");
    public static final String SELECT_CUSTOMER_ACCOUNTS = load("scripts/rtp-select-customer-accounts.sql");
    public static final String SELECT_EXTERNAL_ADDRESSES = load("scripts/rtp-select-external-addresses.sql");

    public static final String SELECT_HOME_LEGAL_ENTITY =
            "SELECT legal_entity_id FROM rtp.legal_entities ORDER BY created_at LIMIT 1";
    public static final String SELECT_CLEARING_ACCOUNT =
            "SELECT account_id FROM rtp.accounts WHERE account_type = 'INTERNAL_CLEARING' AND geo_partition = ? LIMIT 1";
    public static final String COUNT_LEGAL_ENTITIES = "SELECT count(*) FROM rtp.legal_entities";
    public static final String DROP_SCHEMA = "DROP SCHEMA IF EXISTS rtp CASCADE";
    public static final String SELECT_SERVER_REGION = "SELECT yb_server_region()";
    private static final String SCHEMA_RESOURCE = "scripts/rtp-schema.sql";

    private RtpSql() {
    }

    public static String loadSchemaSql() {
        return load(SCHEMA_RESOURCE);
    }

    private static String load(String resourcePath) {
        InputStream in = RtpSql.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource " + resourcePath);
        }
        try {
            return copyToString(in);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read " + resourcePath, e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // resource close is best-effort
            }
        }
    }

    private static String copyToString(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
