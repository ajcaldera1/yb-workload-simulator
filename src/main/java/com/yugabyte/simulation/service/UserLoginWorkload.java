package com.yugabyte.simulation.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.workload.Step;
import com.yugabyte.simulation.workload.WorkloadSimulationBase;

@Repository
public class UserLoginWorkload extends WorkloadSimulationBase implements WorkloadSimulation {

    private static final double NEW_USER_FRACTION = 0.20;
    private static final int MIN_DEVICES_PER_USER = 1;
    private static final int MAX_DEVICES_PER_USER = 3;
    private static final int MIN_LOGIN_DURATION_SECONDS = 1;
    private static final int MAX_LOGIN_DURATION_SECONDS = 32765;

    private static final String[] DEVICE_TYPES = {"M", "T", "D", "W"};

    private static final String DROP_LOGIN_HISTORY = "DROP TABLE IF EXISTS login_history;";
    private static final String DROP_DEVICES = "DROP TABLE IF EXISTS devices;";
    private static final String DROP_ACCOUNTS = "DROP TABLE IF EXISTS accounts;";

    private static final String CREATE_ACCOUNTS =
            "CREATE TABLE accounts ("
                    + "account_id BIGSERIAL NOT NULL PRIMARY KEY,"
                    + "email_address TEXT NOT NULL,"
                    + "first_name TEXT NOT NULL,"
                    + "last_name TEXT NOT NULL,"
                    + "account_creation_date DATE NOT NULL,"
                    + "last_login TIMESTAMP"
                    + ");";

    private static final String CREATE_DEVICES =
            "CREATE TABLE devices ("
                    + "device_id BIGSERIAL NOT NULL,"
                    + "account_id BIGINT NOT NULL,"
                    + "device_name TEXT NOT NULL,"
                    + "device_address TEXT NOT NULL,"
                    + "device_type CHAR(1) NOT NULL,"
                    + "last_login TIMESTAMP,"
                    + "PRIMARY KEY (account_id, device_id)"
                    + ");";

    private static final String CREATE_LOGIN_HISTORY =
            "CREATE TABLE login_history ("
                    + "device_id BIGINT NOT NULL,"
                    + "account_id BIGINT NOT NULL,"
                    + "login_timestamp TIMESTAMP NOT NULL DEFAULT clock_timestamp(),"
                    + "duration SMALLINT,"
                    + "PRIMARY KEY ((device_id, account_id) HASH, login_timestamp)"
                    + ");";

    private static final String INSERT_ACCOUNT =
            "INSERT INTO accounts (account_id, email_address, first_name, last_name, account_creation_date, last_login)"
                    + " VALUES (?, ?, ?, ?, ?, NULL);";

    private static final String INSERT_DEVICE =
            "INSERT INTO devices (device_id, account_id, device_name, device_address, device_type, last_login)"
                    + " VALUES (?, ?, ?, ?, ?, NULL);";

    private static final String SELECT_DEVICE_IDS_BY_ACCOUNT =
            "SELECT device_id FROM devices WHERE account_id = ?;";

    private static final String UPDATE_ACCOUNT_LAST_LOGIN =
            "UPDATE accounts SET last_login = ? WHERE account_id = ?;";

    private static final String UPDATE_DEVICE_LAST_LOGIN =
            "UPDATE devices SET last_login = ? WHERE account_id = ? AND device_id = ?;";

    private static final String INSERT_LOGIN_HISTORY =
            "INSERT INTO login_history (device_id, account_id, login_timestamp, duration) VALUES (?, ?, ?, ?);";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${SPRING_APPLICATION_NAME:}")
    private String applicationName;

    @Autowired
    @Lazy
    private UserLoginWorkload self;

    private enum WorkloadType {
        CREATE_TABLES,
        SEED_DATA,
        RUN_SIMULATION
    }

    @Override
    public String getName() {
        return "USER_LOGIN" + ((applicationName != null && !applicationName.isEmpty()) ? " [" + applicationName + "]" : "");
    }

    @Override
    public List<WorkloadDesc> getWorkloads() {
        return Arrays.asList(
                new WorkloadDesc(
                        WorkloadType.CREATE_TABLES.toString(),
                        "Create Tables",
                        "Create accounts, devices, and login_history. Drop if they exist."
                )
                        .setDescription("Create the database tables. If tables already exist they will be dropped.")
                        .onInvoke((runner, params) -> {
                            runner.newFixedStepsInstance(
                                    new Step("Drop login_history", (a, b) -> jdbcTemplate.execute(DROP_LOGIN_HISTORY)),
                                    new Step("Drop devices", (a, b) -> jdbcTemplate.execute(DROP_DEVICES)),
                                    new Step("Drop accounts", (a, b) -> jdbcTemplate.execute(DROP_ACCOUNTS)),
                                    new Step("Create accounts", (a, b) -> jdbcTemplate.execute(CREATE_ACCOUNTS)),
                                    new Step("Create devices", (a, b) -> jdbcTemplate.execute(CREATE_DEVICES)),
                                    new Step("Create login_history", (a, b) -> jdbcTemplate.execute(CREATE_LOGIN_HISTORY))
                            ).execute();
                        }),

                new WorkloadDesc(
                        WorkloadType.SEED_DATA.toString(),
                        "Seed Data",
                        "Load accounts and devices (1-3 devices per account)",
                        new WorkloadParamDesc("Number of accounts", 1, Integer.MAX_VALUE, 10_000),
                        new WorkloadParamDesc("Threads", 1, 500, 32)
                )
                        .setDescription("Load accounts and devices. No login_history rows are created.")
                        .onInvoke((runner, params) -> {
                            jdbcTemplate.setFetchSize(1000);
                            AtomicLong nextAccountId = new AtomicLong(1);
                            runner.newFixedTargetInstance()
                                    .setCustomData(nextAccountId)
                                    .execute(params.asInt(1), params.asInt(0),
                                            (customData, threadData) -> {
                                                long accountId = ((AtomicLong) customData).getAndIncrement();
                                                self.seedAccount(accountId);
                                                return null;
                                            });
                        }),

                new WorkloadDesc(
                        WorkloadType.RUN_SIMULATION.toString(),
                        "Run Simulation",
                        "Simulate user logins; ~20% of invocations register new users",
                        new WorkloadParamDesc("Invocations", 1, Integer.MAX_VALUE, 100_000),
                        new WorkloadParamDesc("Seeded accounts", 1, Integer.MAX_VALUE, 10_000),
                        new WorkloadParamDesc("Threads", 1, 500, 32)
                )
                        .setDescription("80%: pick account and device, update last_login, insert login_history. 20%: new account and 1-3 devices only.")
                        .onInvoke((runner, params) -> {
                            jdbcTemplate.setFetchSize(1000);
                            int seededAccounts = params.asInt(1);
                            AtomicLong nextAccountId = new AtomicLong(seededAccounts + 1L);
                            runner.newFixedTargetInstance()
                                    .setCustomData(nextAccountId)
                                    .execute(params.asInt(2), params.asInt(0),
                                            (customData, threadData) -> {
                                                if (LoadGeneratorUtils.getDouble() < NEW_USER_FRACTION) {
                                                    self.registerNewUser((AtomicLong) customData);
                                                } else {
                                                    self.performLogin(seededAccounts);
                                                }
                                                return null;
                                            });
                        })
        );
    }

    @Transactional
    public void seedAccount(long accountId) {
        insertAccount(accountId);
        insertDevicesForAccount(accountId);
    }

    @Transactional
    public void registerNewUser(AtomicLong nextAccountId) {
        long accountId = nextAccountId.getAndIncrement();
        insertAccount(accountId);
        insertDevicesForAccount(accountId);
    }

    @Transactional
    public void performLogin(int seededAccounts) {
        if (seededAccounts <= 0) {
            return;
        }
        long accountId = ThreadLocalRandom.current().nextLong(1, seededAccounts + 1L);
        List<Long> deviceIds = jdbcTemplate.query(
                SELECT_DEVICE_IDS_BY_ACCOUNT,
                (rs, rowNum) -> rs.getLong("device_id"),
                accountId);
        if (deviceIds.isEmpty()) {
            return;
        }
        long deviceId = deviceIds.get(ThreadLocalRandom.current().nextInt(deviceIds.size()));
        Timestamp now = LoadGeneratorUtils.getTimestamp();
        short duration = (short) LoadGeneratorUtils.getInt(MIN_LOGIN_DURATION_SECONDS, MAX_LOGIN_DURATION_SECONDS + 1);

        jdbcTemplate.update(UPDATE_ACCOUNT_LAST_LOGIN, now, accountId);
        jdbcTemplate.update(UPDATE_DEVICE_LAST_LOGIN, now, accountId, deviceId);
        jdbcTemplate.update(INSERT_LOGIN_HISTORY, deviceId, accountId, now, duration);
    }

    private void insertAccount(long accountId) {
        String[] nameParts = splitName(LoadGeneratorUtils.getName());
        String email = LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(6, 16)).toLowerCase() + "@example.com";
        Date creationDate = randomAccountCreationDate();
        jdbcTemplate.update(INSERT_ACCOUNT, accountId, email, nameParts[0], nameParts[1], creationDate);
    }

    private void insertDevicesForAccount(long accountId) {
        int deviceCount = LoadGeneratorUtils.getInt(MIN_DEVICES_PER_USER, MAX_DEVICES_PER_USER + 1);
        for (int deviceIndex = 1; deviceIndex <= deviceCount; deviceIndex++) {
            jdbcTemplate.update(
                    INSERT_DEVICE,
                    (long) deviceIndex,
                    accountId,
                    "Device-" + deviceIndex,
                    randomDeviceAddress(),
                    LoadGeneratorUtils.oneOf(DEVICE_TYPES));
        }
    }

    private static String[] splitName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return new String[]{"User", "Account"};
        }
        int space = fullName.indexOf(' ');
        if (space < 0) {
            return new String[]{fullName, "User"};
        }
        return new String[]{fullName.substring(0, space), fullName.substring(space + 1)};
    }

    private static Date randomAccountCreationDate() {
        int daysAgo = LoadGeneratorUtils.getInt(1, 3651);
        return Date.valueOf(LocalDate.now().minusDays(daysAgo));
    }

    private static String randomDeviceAddress() {
        return LoadGeneratorUtils.getInt(1, 256) + "."
                + LoadGeneratorUtils.getInt(0, 256) + "."
                + LoadGeneratorUtils.getInt(0, 256) + "."
                + LoadGeneratorUtils.getInt(0, 256);
    }
}
