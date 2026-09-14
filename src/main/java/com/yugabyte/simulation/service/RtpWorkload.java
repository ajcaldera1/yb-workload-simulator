package com.yugabyte.simulation.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.service.rtp.RtpPaymentBatch;
import com.yugabyte.simulation.service.rtp.RtpPaymentFactory;
import com.yugabyte.simulation.service.rtp.RtpPopulation;
import com.yugabyte.simulation.service.rtp.RtpSql;
import com.yugabyte.simulation.util.SqlScriptExecutor;
import com.yugabyte.simulation.workload.Step;
import com.yugabyte.simulation.workload.WorkloadSimulationBase;

/**
 * Real-time payments workload adapted from {@code rtp_demo}: geo-partitioned YSQL
 * schema plus the generator's CTE seed and payment-lifecycle algorithm.
 */
@Repository
public class RtpWorkload extends WorkloadSimulationBase implements WorkloadSimulation {

    static final String[] REGIONS = {"us-east-1", "us-west-2", "eu-west-1"};
    private static final String[] GEO_CHOICES = {"auto", "us-east-1", "us-west-2", "eu-west-1"};

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RetryTemplate retryTemplate;

    @Value("${SPRING_APPLICATION_NAME:}")
    private String applicationName;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private enum WorkloadType {
        CREATE_TABLES,
        SEED_DATA,
        RUN_SIMULATION,
        RUN_SIMULATION_TPS
    }

    @Override
    public String getName() {
        return "RTP" + ((applicationName != null && !applicationName.isEmpty()) ? " [" + applicationName + "]" : "");
    }

    @Override
    public List<WorkloadDesc> getWorkloads() {
        return Arrays.asList(
                new WorkloadDesc(
                        WorkloadType.CREATE_TABLES.toString(),
                        "Create Tables",
                        "Drop and recreate the geo-partitioned rtp schema."
                )
                        .setDescription("Creates the rtp schema, enums, tables, indexes, and CTE helper functions. Existing rtp objects are dropped.")
                        .onInvoke((runner, params) -> runner.newFixedStepsInstance(
                                new Step("Drop rtp schema", (a, b) -> jdbcTemplate.execute(RtpSql.DROP_SCHEMA)),
                                new Step("Apply RTP schema", (a, b) -> applySchema())
                        ).execute()),

                new WorkloadDesc(
                        WorkloadType.SEED_DATA.toString(),
                        "Seed Data",
                        "Load legal entities, customers, accounts, and off-us counterparties",
                        new WorkloadParamDesc("Number of customers", 1, Integer.MAX_VALUE, 2_000),
                        new WorkloadParamDesc("Number of externals", 1, Integer.MAX_VALUE, 500),
                        new WorkloadParamDesc("Threads", 1, 500, 16)
                )
                        .setDescription("Seeds globally replicated legal entities, per-region clearing accounts, bank customers (parties+accounts), and external counterparties (parties+payment_addresses).")
                        .onInvoke((runner, params) -> {
                            int customers = params.asInt(0);
                            int externals = params.asInt(1);
                            int threads = params.asInt(2);
                            UUID homeId = seedReferenceData();
                            SeedState state = new SeedState(homeId, customers);
                            runner.newFixedTargetInstance()
                                    .setCustomData(state)
                                    .execute(threads, customers + externals, (customData, threadData) -> {
                                        SeedState seed = (SeedState) customData;
                                        long index = seed.nextIndex.getAndIncrement();
                                        if (index < seed.customerCount) {
                                            seedCustomer(index, seed.homeLedgerEntityId);
                                        } else {
                                            seedExternal(index - seed.customerCount);
                                        }
                                        return null;
                                    });
                        }),

                new WorkloadDesc(
                        WorkloadType.RUN_SIMULATION.toString(),
                        "Simulation",
                        "Insert payment lifecycles with one CTE per batch",
                        new WorkloadParamDesc("Invocations", 1, Integer.MAX_VALUE, 1_000),
                        new WorkloadParamDesc("Payments per batch", 1, 500, 25),
                        new WorkloadParamDesc("Threads", 1, 500, 16),
                        new WorkloadParamDesc("Geo partition", 0, GEO_CHOICES),
                        new WorkloadParamDesc("Sample size", 100, Integer.MAX_VALUE, 10_000)
                )
                        .setDescription("Each invocation inserts a batch of complete payment graphs (idempotency, payment, events, screening, reservation, balanced ledger, outbox, audit) in one CTE.")
                        .onInvoke((runner, params) -> {
                            int invocations = params.asInt(0);
                            int batchSize = params.asInt(1);
                            int threads = params.asInt(2);
                            String geo = resolveGeoPartition(params.asString(3));
                            int sampleSize = Math.max(params.asInt(4), batchSize * 4);
                            RtpPopulation population = loadPopulation(geo, sampleSize);
                            runner.newFixedTargetInstance()
                                    .setCustomData(population)
                                    .execute(threads, invocations, (customData, threadData) -> {
                                        RtpPaymentFactory factory = threadData instanceof RtpPaymentFactory
                                                ? (RtpPaymentFactory) threadData
                                                : new RtpPaymentFactory((RtpPopulation) customData, ThreadLocalRandom.current());
                                        insertGeneratedBatch(factory, batchSize);
                                        return factory;
                                    });
                        }),

                new WorkloadDesc(
                        WorkloadType.RUN_SIMULATION_TPS.toString(),
                        "Simulation - TPS",
                        "Sustain a target rate of payment-batch CTEs",
                        new WorkloadParamDesc("Throughput (tps)", 1, 1_000_000, 1_000),
                        new WorkloadParamDesc("Max Threads", 1, 500, 32),
                        new WorkloadParamDesc("Payments per batch", 1, 500, 1),
                        new WorkloadParamDesc("Geo partition", 0, GEO_CHOICES),
                        new WorkloadParamDesc("Sample size", 100, Integer.MAX_VALUE, 10_000)
                )
                        .setDescription("Unbounded stream of the same CTE payment write path, paced to the requested throughput. Default batch size 1 so TPS tracks payments/sec.")
                        .onInvoke((runner, params) -> {
                            int tps = params.asInt(0);
                            int maxThreads = params.asInt(1);
                            int batchSize = params.asInt(2);
                            String geo = resolveGeoPartition(params.asString(3));
                            int sampleSize = Math.max(params.asInt(4), batchSize * 4);
                            RtpPopulation population = loadPopulation(geo, sampleSize);
                            runner.newThroughputWorkloadInstance()
                                    .setMaxThreads(maxThreads)
                                    .setCustomData(population)
                                    .execute(tps, (customData, threadData) -> insertGeneratedBatch(
                                            new RtpPaymentFactory((RtpPopulation) customData, ThreadLocalRandom.current()),
                                            batchSize));
                        })
        );
    }

    private void applySchema() {
        try (Connection conn = jdbcTemplate.getDataSource().getConnection()) {
            conn.setAutoCommit(true);
            SqlScriptExecutor.execute(conn, RtpSql.loadSchemaSql());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to apply RTP schema", e);
        }
    }

    private UUID seedReferenceData() {
        Integer existing = jdbcTemplate.queryForObject(RtpSql.COUNT_LEGAL_ENTITIES, Integer.class);
        UUID homeId;
        if (existing == null || existing == 0) {
            homeId = jdbcTemplate.queryForObject(
                    RtpSql.INSERT_LEGAL_ENTITIES_SQL,
                    UUID.class,
                    "Meridian Trust Bank, N.A.", randomLei(), "071000288", "MERIUS33XXX",
                    "Northwind Correspondent Bank", randomLei(), randomRouting(), randomBic(),
                    "Harbor Point Federal Credit Union", randomLei(), randomRouting(), randomBic(),
                    "Meridian Trust Bank, N.A.");
        } else {
            homeId = queryUuid(RtpSql.SELECT_HOME_LEGAL_ENTITY);
        }
        if (homeId == null) {
            throw new IllegalStateException("Failed to seed legal entities");
        }
        for (String geo : REGIONS) {
            UUID clearing = queryUuid(RtpSql.SELECT_CLEARING_ACCOUNT, geo);
            if (clearing == null) {
                jdbcTemplate.queryForObject(
                        RtpSql.INSERT_CLEARING_ACCOUNT_SQL,
                        UUID.class,
                        geo,
                        homeId,
                        "Meridian Trust Bank - Operations (" + geo + ")",
                        "BANK-OPS-" + geo,
                        "ops@meridiantrust.example",
                        homeId,
                        "CLR" + LoadGeneratorUtils.getHexString(16));
            }
        }
        return homeId;
    }

    private void seedCustomer(long index, UUID homeId) {
        UUID partyId = UUID.randomUUID();
        String geo = REGIONS[(int) (index % REGIONS.length)];
        boolean business = ThreadLocalRandom.current().nextDouble() < 0.25;
        List<Map<String, Object>> parties = Collections.singletonList(
                RtpPaymentFactory.customerParty(partyId, geo, index, business));
        int accountCount = ThreadLocalRandom.current().nextDouble() < 0.2 ? 2 : 1;
        List<Map<String, Object>> accounts = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            accounts.add(RtpPaymentFactory.customerAccount(
                    UUID.randomUUID(), partyId, geo, homeId, ThreadLocalRandom.current()));
        }
        retryTemplate.execute(context -> {
            jdbcTemplate.update(RtpSql.SEED_PARTY_ACCOUNT_SQL, toJson(parties), toJson(accounts));
            return null;
        });
    }

    private void seedExternal(long index) {
        UUID partyId = UUID.randomUUID();
        String geo = REGIONS[(int) (index % REGIONS.length)];
        boolean business = ThreadLocalRandom.current().nextDouble() < 0.35;
        List<Map<String, Object>> parties = Collections.singletonList(
                RtpPaymentFactory.externalParty(partyId, geo, index, business));
        List<Map<String, Object>> addresses = Collections.singletonList(
                RtpPaymentFactory.externalAddress(partyId, geo, ThreadLocalRandom.current()));
        retryTemplate.execute(context -> {
            jdbcTemplate.update(RtpSql.SEED_PARTY_ADDRESS_SQL, toJson(parties), toJson(addresses));
            return null;
        });
    }

    private RtpPopulation loadPopulation(String geoPartition, int sampleSize) {
        UUID homeId = queryUuid(RtpSql.SELECT_HOME_LEGAL_ENTITY);
        UUID clearingId = queryUuid(RtpSql.SELECT_CLEARING_ACCOUNT, geoPartition);
        if (homeId == null || clearingId == null) {
            throw new IllegalStateException("No seeded base data found. Run Seed Data first.");
        }
        List<RtpPopulation.CustomerAccount> customers = jdbcTemplate.query(
                RtpSql.SELECT_CUSTOMER_ACCOUNTS,
                (rs, rowNum) -> new RtpPopulation.CustomerAccount(
                        readUuid(rs, "account_id"),
                        readUuid(rs, "owning_party_id"),
                        rs.getString("currency_code"),
                        rs.getString("geo_partition")),
                geoPartition, sampleSize);
        List<RtpPopulation.ExternalAddress> externals = jdbcTemplate.query(
                RtpSql.SELECT_EXTERNAL_ADDRESSES,
                (rs, rowNum) -> new RtpPopulation.ExternalAddress(
                        readUuid(rs, "party_id"),
                        readUuid(rs, "payment_address_id"),
                        rs.getString("rail"),
                        rs.getString("geo_partition")),
                geoPartition, sampleSize);
        if (customers.isEmpty() || externals.isEmpty()) {
            throw new IllegalStateException(
                    "No seeded base data for geo_partition=" + geoPartition + ". Run Seed Data first.");
        }
        return new RtpPopulation(homeId, geoPartition, clearingId, customers, externals);
    }

    private void insertGeneratedBatch(RtpPaymentFactory factory, int batchSize) {
        RtpPaymentBatch batch = new RtpPaymentBatch();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int i = 0; i < batchSize; i++) {
            factory.buildOne(batch, now);
        }
        retryTemplate.execute(context -> {
            jdbcTemplate.update(
                    RtpSql.BATCH_INSERT_SQL,
                    toJson(batch.idempotencyKeys),
                    toJson(batch.payments),
                    toJson(batch.paymentEvents),
                    toJson(batch.accountReservations),
                    toJson(batch.ledgerTransactions),
                    toJson(batch.ledgerPostings),
                    toJson(batch.screeningResults),
                    toJson(batch.paymentReviews),
                    toJson(batch.paymentReturns),
                    toJson(batch.outboxEvents),
                    toJson(batch.auditLog));
            return null;
        });
    }

    private String resolveGeoPartition(String override) {
        if (override != null && !override.trim().isEmpty() && !"auto".equalsIgnoreCase(override)) {
            return override;
        }
        try {
            String region = jdbcTemplate.queryForObject(RtpSql.SELECT_SERVER_REGION, String.class);
            if (region != null && !region.trim().isEmpty()) {
                return region;
            }
        } catch (Exception ignored) {
            // Local clusters may not implement yb_server_region().
        }
        return "us-east-1";
    }

    private String toJson(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize RTP JSON payload", e);
        }
    }

    private UUID queryUuid(String sql, Object... args) {
        try {
            return jdbcTemplate.query(sql, rs -> rs.next() ? readUuid(rs, 1) : null, args);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private static UUID readUuid(ResultSet rs, String column) throws SQLException {
        return toUuid(rs.getObject(column));
    }

    private static UUID readUuid(ResultSet rs, int column) throws SQLException {
        return toUuid(rs.getObject(column));
    }

    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID) {
            return (UUID) value;
        }
        return UUID.fromString(value.toString());
    }

    private static String randomLei() {
        return LoadGeneratorUtils.getAlphaString(20);
    }

    private static String randomRouting() {
        return LoadGeneratorUtils.getFixedLengthNumber(9);
    }

    private static String randomBic() {
        return LoadGeneratorUtils.getAlphaString(8) + "XXX";
    }

    private static final class SeedState {
        final UUID homeLedgerEntityId;
        final int customerCount;
        final AtomicLong nextIndex = new AtomicLong(0);

        SeedState(UUID homeLedgerEntityId, int customerCount) {
            this.homeLedgerEntityId = homeLedgerEntityId;
            this.customerCount = customerCount;
        }
    }
}
