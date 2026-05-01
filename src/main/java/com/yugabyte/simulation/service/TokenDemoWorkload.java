package com.yugabyte.simulation.service;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.workload.Step;
import com.yugabyte.simulation.workload.WorkloadSimulationBase;

@Repository
public class TokenDemoWorkload extends WorkloadSimulationBase implements WorkloadSimulation {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${SPRING_APPLICATION_NAME:}")
    private String applicationName;

    @Autowired
    @Lazy
    private TokenDemoWorkload self;

    private static final int TOKEN_ACCT_NUM_LENGTH = 19;

    // DDL - use standard YSQL primary key; use (token_acct_num HASH) if your YugabyteDB version requires it
    private static final String DROP_TOKEN_MAP_HIST = "drop table if exists token_map_hist;";
    private static final String DROP_TOKEN_MAP = "drop table if exists token_map;";

    private static final String CREATE_TOKEN_MAP =
            "create table if not exists token_map ("
                    + "token_acct_num character varying(19) not null,"
                    + "token_seq_num character varying(3),"
                    + "token_uniq_ref_id character varying(64),"
                    + "token_stat_cd character(1) not null,"
                    + "token_expir_dt character varying(8),"
                    + "token_type_cd character(1),"
                    + "token_rqstr_id numeric(11,0) not null,"
                    + "token_strg_type_cd character varying(2),"
                    + "token_assrnc_lvl_num numeric(2,0),"
                    + "funding_acct_num character varying(19) not null,"
                    + "fpan_expir_dt character varying(8),"
                    + "fpan_seq_num character varying(3),"
                    + "fincl_acct_id character varying(64),"
                    + "cust_ica numeric(11,0),"
                    + "num_cntry_cd character varying(3),"
                    + "wlt_prvdr_id character varying(3) not null,"
                    + "mbl_wlt_id character varying(30) not null,"
                    + "mcbp_prcss_type_cd character varying(2),"
                    + "key_derivation_indx character varying(3),"
                    + "cryptgrm_ver_num character varying(2),"
                    + "aip_list_id numeric(5,0),"
                    + "prim_cvm_list_id numeric(3,0),"
                    + "alt_cvm_list_id numeric(3,0),"
                    + "dsbld_paymt_chnl numeric(4,0),"
                    + "crte_ts timestamp without time zone not null,"
                    + "updt_ts timestamp without time zone not null,"
                    + "rplctn_updt_ts timestamp without time zone default clock_timestamp() not null,"
                    + "hash_token_map_txt character varying(128),"
                    + "hash_token_acct_num character varying(128),"
                    + "hash_fund_acct_num character varying(128),"
                    + "hash_fincl_acct_id character varying(128),"
                    + "constraint token_map_pkey primary key (token_acct_num)"
                    + ");";

    private static final String CREATE_INDEX_HASH_TOKEN_ACCT_NUM =
            "create index hash_token_acct_num_idx01 on token_map (hash_token_acct_num)";
    private static final String CREATE_INDEX_FUNDING_ACCT_NUM =
            "create index funding_acct_num_idx02 on token_map (funding_acct_num)";
    private static final String CREATE_INDEX_NUM_CNTRY_CD =
            "create index num_cntry_cd_idx03 on token_map (num_cntry_cd)";

    private static final String CREATE_TOKEN_MAP_HIST =
            "create table if not exists token_map_hist ("
                    + "token_acct_num character varying(19) not null,"
                    + "token_seq_num character varying(3),"
                    + "token_uniq_ref_id character varying(64),"
                    + "token_stat_cd character(1) not null,"
                    + "token_rqstr_id numeric(11,0) not null,"
                    + "token_strg_type_cd character varying(2),"
                    + "token_assrnc_lvl_num numeric(2,0),"
                    + "funding_acct_num character varying(19) not null,"
                    + "fpan_expir_dt character varying(8),"
                    + "fpan_seq_num character varying(3),"
                    + "fincl_acct_id character varying(64),"
                    + "cust_ica numeric(11,0),"
                    + "num_cntry_cd character varying(3),"
                    + "wlt_prvdr_id character varying(3) not null,"
                    + "mbl_wlt_id character varying(30) not null,"
                    + "mcbp_prcss_type_cd character varying(2),"
                    + "key_derivation_indx character varying(3),"
                    + "cryptgrm_ver_num character varying(2),"
                    + "aip_list_id numeric(5,0),"
                    + "prim_cvm_list_id numeric(3,0),"
                    + "alt_cvm_list_id numeric(3,0),"
                    + "dsbld_paymt_chnl numeric(4,0),"
                    + "hash_token_acct_num character varying(128),"
                    + "updt_epoch_ms_num numeric(20,0) not null,"
                    + "constraint token_map_hist_pkey primary key (token_acct_num, updt_epoch_ms_num)"
                    + ");";

    private static final String SELECT_TOKEN_MAP =
            "select token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_expir_dt, token_type_cd,"
                    + " token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num, funding_acct_num, fpan_expir_dt, fpan_seq_num,"
                    + " fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd,"
                    + " key_derivation_indx, cryptgrm_ver_num, aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl,"
                    + " crte_ts, updt_ts, rplctn_updt_ts, hash_token_map_txt, hash_token_acct_num, hash_fund_acct_num, hash_fincl_acct_id"
                    + " from token_map where token_acct_num = ?";

    private static final String INSERT_TOKEN_MAP_HIST =
            "insert into token_map_hist (token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_rqstr_id,"
                    + " token_strg_type_cd, token_assrnc_lvl_num, funding_acct_num, fpan_expir_dt, fpan_seq_num, fincl_acct_id,"
                    + " cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd, key_derivation_indx, cryptgrm_ver_num,"
                    + " aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, hash_token_acct_num, updt_epoch_ms_num)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String INSERT_TOKEN_MAP =
            "insert into token_map (token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_expir_dt, token_type_cd,"
                    + " token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num, funding_acct_num, fpan_expir_dt, fpan_seq_num,"
                    + " fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd, key_derivation_indx, cryptgrm_ver_num,"
                    + " aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, crte_ts, updt_ts, rplctn_updt_ts,"
                    + " hash_token_map_txt, hash_token_acct_num, hash_fund_acct_num)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String UPDATE_TOKEN_MAP_FULL =
            "update token_map set token_seq_num=?, token_uniq_ref_id=?, token_stat_cd=?, token_expir_dt=?, token_type_cd=?,"
                    + " token_rqstr_id=?, token_strg_type_cd=?, token_assrnc_lvl_num=?, funding_acct_num=?, fpan_expir_dt=?, fpan_seq_num=?,"
                    + " fincl_acct_id=?, cust_ica=?, num_cntry_cd=?, wlt_prvdr_id=?, mbl_wlt_id=?, mcbp_prcss_type_cd=?, key_derivation_indx=?, cryptgrm_ver_num=?,"
                    + " aip_list_id=?, prim_cvm_list_id=?, alt_cvm_list_id=?, dsbld_paymt_chnl=?, crte_ts=?, updt_ts=?, rplctn_updt_ts=?,"
                    + " hash_token_map_txt=?, hash_token_acct_num=?, hash_fund_acct_num=?, hash_fincl_acct_id=? where token_acct_num=?";

    private enum WorkloadType {
        CREATE_TABLES,
        SEED_DATA,
        RUN_SIMULATION
    }

    @Override
    public String getName() {
        return "TOKEN_DEMO" + ((applicationName != null && !applicationName.isEmpty()) ? " [" + applicationName + "]" : "");
    }

    @Override
    public List<WorkloadDesc> getWorkloads() {
        return Arrays.asList(
                new WorkloadDesc(
                        WorkloadType.CREATE_TABLES.toString(),
                        "Create Tables",
                        "Create token_map and token_map_hist. Drop if they exist."
                )
                        .setDescription("Create the database tables. If tables already exist they will be dropped.")
                        .onInvoke((runner, params) -> {
                            runner.newFixedStepsInstance(
                                    new Step("Drop token_map_hist", (a, b) -> jdbcTemplate.execute(DROP_TOKEN_MAP_HIST)),
                                    new Step("Drop token_map", (a, b) -> jdbcTemplate.execute(DROP_TOKEN_MAP)),
                                    new Step("Create token_map", (a, b) -> jdbcTemplate.execute(CREATE_TOKEN_MAP)),
                                    new Step("Create token_map_hist", (a, b) -> jdbcTemplate.execute(CREATE_TOKEN_MAP_HIST)),
                                    new Step("Create index hash_token_acct_num_idx01", (a, b) -> jdbcTemplate.execute(CREATE_INDEX_HASH_TOKEN_ACCT_NUM)),
                                    new Step("Create index funding_acct_num_idx02", (a, b) -> jdbcTemplate.execute(CREATE_INDEX_FUNDING_ACCT_NUM)),
                                    new Step("Create index num_cntry_cd_idx03", (a, b) -> jdbcTemplate.execute(CREATE_INDEX_NUM_CNTRY_CD))
                            ).execute();
                        }),

                new WorkloadDesc(
                        WorkloadType.SEED_DATA.toString(),
                        "Seed Data",
                        "Fill token_map with sample rows",
                        new WorkloadParamDesc("Number of records", 1, Integer.MAX_VALUE, 10_000),
                        new WorkloadParamDesc("Threads", 1, 500, 32)
                )
                        .setDescription("Load data into the token_map table.")
                        .onInvoke((runner, params) -> {
                            jdbcTemplate.setFetchSize(1000);
                            AtomicLong nextId = new AtomicLong(1);
                            runner.newFixedTargetInstance()
                                    .setCustomData(nextId)
                                    .execute(params.asInt(1), params.asInt(0),
                                            (customData, threadData) -> {
                                                insertTokenMapRow((AtomicLong) customData);
                                                return null;
                                            });
                        }),

                new WorkloadDesc(
                        WorkloadType.RUN_SIMULATION.toString(),
                        "Run Simulation",
                        "Generate a new record; 50% use existing token_acct_num (hist + update), 50% use new (insert)",
                        new WorkloadParamDesc("Invocations", 1, Integer.MAX_VALUE, 100_000),
                        new WorkloadParamDesc("Seeded rows (max token id)", 1, Integer.MAX_VALUE, 10_000),
                        new WorkloadParamDesc("Threads", 1, 500, 32)
                )
                        .setDescription("Always generate new values. 50% use existing token_acct_num: copy current row to token_map_hist then update token_map with new values. 50% use new token_acct_num: insert new row.")
                        .onInvoke((runner, params) -> {
                            jdbcTemplate.setFetchSize(1000);
                            int seededRows = params.asInt(1);
                            AtomicLong nextNewTokenId = new AtomicLong(seededRows + 1);
                            runner.newFixedTargetInstance()
                                    .setCustomData(nextNewTokenId)
                                    .execute(params.asInt(2), params.asInt(0),
                                            (customData, threadData) -> {
                                                self.selectUpdateAndHist(seededRows, (AtomicLong) customData);
                                                return null;
                                            });
                        })
        );
    }

    private static String padTokenAcctNum(long id) {
        String s = String.valueOf(id);
        if (s.length() >= TOKEN_ACCT_NUM_LENGTH) return s.substring(0, TOKEN_ACCT_NUM_LENGTH);
        StringBuilder sb = new StringBuilder(TOKEN_ACCT_NUM_LENGTH);
        for (int i = s.length(); i < TOKEN_ACCT_NUM_LENGTH; i++) sb.append('0');
        sb.append(s);
        return sb.toString();
    }

    /** Returns an 8-digit date string YYYYMMDD for a random date in the future (1 day to 10 years from today). */
    private static String randomFutureDateYyyyMmDd() {
        LocalDate today = LocalDate.now();
        int daysFromNow = LoadGeneratorUtils.getInt(1, 10 * 365 + 1);
        LocalDate future = today.plusDays(daysFromNow);
        int y = future.getYear();
        int m = future.getMonthValue();
        int d = future.getDayOfMonth();
        return String.format("%04d%02d%02d", y, m, d);
    }

    /** Generates a full set of new token_map values. First element is token_acct_num (caller must set). Returns 31 elements in INSERT column order. */
    private static Object[] generateNewTokenMapValues(String tokenAcctNum) {
        Timestamp now = LoadGeneratorUtils.getTimestamp();
        return new Object[]{
                tokenAcctNum,
                LoadGeneratorUtils.getFixedLengthNumber(3),
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(8, 64)),
                LoadGeneratorUtils.oneOf(new String[]{"A", "I", "S"}),
                randomFutureDateYyyyMmDd(),
                LoadGeneratorUtils.oneOf(new String[]{"D", "C", "T"}),
                LoadGeneratorUtils.getLong(1, 999_999_999_99L),
                LoadGeneratorUtils.getAlphaString(2),
                LoadGeneratorUtils.getInt(0, 100),
                LoadGeneratorUtils.getFixedLengthNumber(19),
                randomFutureDateYyyyMmDd(),
                LoadGeneratorUtils.getFixedLengthNumber(3),
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(8, 64)),
                LoadGeneratorUtils.getLong(1, 999_999_999_99L),
                LoadGeneratorUtils.getFixedLengthNumber(3),
                LoadGeneratorUtils.getAlphaString(3),
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(10, 30)),
                LoadGeneratorUtils.getAlphaString(2),
                LoadGeneratorUtils.getFixedLengthNumber(3),
                LoadGeneratorUtils.getFixedLengthNumber(2),
                LoadGeneratorUtils.getInt(0, 99999),
                LoadGeneratorUtils.getInt(0, 999),
                LoadGeneratorUtils.getInt(0, 999),
                LoadGeneratorUtils.getInt(0, 9999),
                now,
                now,
                now,
                LoadGeneratorUtils.getAlphaString(LoadGeneratorUtils.getInt(16, 128)),
                LoadGeneratorUtils.getHexString(32),
                LoadGeneratorUtils.getHexString(32),
                LoadGeneratorUtils.getHexString(32),
                LoadGeneratorUtils.getHexString(32)
        };
    }

    private void insertTokenMapRow(AtomicLong nextId) {
        String tokenAcctNum = padTokenAcctNum(nextId.getAndIncrement());
        Object[] values = generateNewTokenMapValues(tokenAcctNum);
        jdbcTemplate.update(INSERT_TOKEN_MAP, Arrays.copyOfRange(values, 0, 30));
    }

    @Transactional
    public void selectUpdateAndHist(int seededRows, AtomicLong nextNewTokenId) {
        if (seededRows <= 0) return;
        // 50% use existing token_acct_num from seeded range; 50% use new (never-seeded) id
        String tokenAcctNum = ThreadLocalRandom.current().nextBoolean()
                ? padTokenAcctNum(ThreadLocalRandom.current().nextInt(1, seededRows + 1))
                : padTokenAcctNum(nextNewTokenId.getAndIncrement());

        Object[] newValues = generateNewTokenMapValues(tokenAcctNum);
        newValues[0] = tokenAcctNum;

        List<TokenMapRow> rows = jdbcTemplate.query(SELECT_TOKEN_MAP, new Object[]{tokenAcctNum}, TOKEN_MAP_ROW_MAPPER);
        if (!rows.isEmpty()) {
            // Record exists: insert current row into token_map_hist, then update token_map with new values
            TokenMapRow row = rows.get(0);
            long updtEpochMs = row.updt_ts.getTime();
            jdbcTemplate.update(INSERT_TOKEN_MAP_HIST,
                    row.token_acct_num, row.token_seq_num, row.token_uniq_ref_id, row.token_stat_cd, row.token_rqstr_id,
                    row.token_strg_type_cd, row.token_assrnc_lvl_num, row.funding_acct_num, row.fpan_expir_dt, row.fpan_seq_num, row.fincl_acct_id,
                    row.cust_ica, row.num_cntry_cd, row.wlt_prvdr_id, row.mbl_wlt_id, row.mcbp_prcss_type_cd, row.key_derivation_indx, row.cryptgrm_ver_num,
                    row.aip_list_id, row.prim_cvm_list_id, row.alt_cvm_list_id, row.dsbld_paymt_chnl, row.hash_token_acct_num, updtEpochMs
            );
            // UPDATE token_map: set columns 2..31 (indices 1..30 of newValues), where token_acct_num = newValues[0]
            jdbcTemplate.update(UPDATE_TOKEN_MAP_FULL,
                    newValues[1], newValues[2], newValues[3], newValues[4], newValues[5], newValues[6], newValues[7], newValues[8],
                    newValues[9], newValues[10], newValues[11], newValues[12], newValues[13], newValues[14], newValues[15], newValues[16],
                    newValues[17], newValues[18], newValues[19], newValues[20], newValues[21], newValues[22], newValues[23], newValues[24],
                    newValues[25], newValues[26], newValues[27], newValues[28], newValues[29], newValues[30], tokenAcctNum
            );
        } else {
            // Record does not exist: insert new row (30 columns; omit hash_fincl_acct_id for compatibility)
            jdbcTemplate.update(INSERT_TOKEN_MAP, Arrays.copyOfRange(newValues, 0, 30));
        }
    }

    private static final RowMapper<TokenMapRow> TOKEN_MAP_ROW_MAPPER = (rs, rowNum) -> mapTokenMapRow(rs);

    private static TokenMapRow mapTokenMapRow(ResultSet rs) throws SQLException {
        TokenMapRow r = new TokenMapRow();
        r.token_acct_num = rs.getString("token_acct_num");
        r.token_seq_num = rs.getString("token_seq_num");
        r.token_uniq_ref_id = rs.getString("token_uniq_ref_id");
        r.token_stat_cd = rs.getString("token_stat_cd");
        r.token_rqstr_id = rs.getBigDecimal("token_rqstr_id");
        r.token_strg_type_cd = rs.getString("token_strg_type_cd");
        r.token_assrnc_lvl_num = rs.getBigDecimal("token_assrnc_lvl_num");
        r.funding_acct_num = rs.getString("funding_acct_num");
        r.fpan_expir_dt = rs.getString("fpan_expir_dt");
        r.fpan_seq_num = rs.getString("fpan_seq_num");
        r.fincl_acct_id = rs.getString("fincl_acct_id");
        r.cust_ica = rs.getBigDecimal("cust_ica");
        r.num_cntry_cd = rs.getString("num_cntry_cd");
        r.wlt_prvdr_id = rs.getString("wlt_prvdr_id");
        r.mbl_wlt_id = rs.getString("mbl_wlt_id");
        r.mcbp_prcss_type_cd = rs.getString("mcbp_prcss_type_cd");
        r.key_derivation_indx = rs.getString("key_derivation_indx");
        r.cryptgrm_ver_num = rs.getString("cryptgrm_ver_num");
        r.aip_list_id = rs.getBigDecimal("aip_list_id");
        r.prim_cvm_list_id = rs.getBigDecimal("prim_cvm_list_id");
        r.alt_cvm_list_id = rs.getBigDecimal("alt_cvm_list_id");
        r.dsbld_paymt_chnl = rs.getBigDecimal("dsbld_paymt_chnl");
        r.hash_token_acct_num = rs.getString("hash_token_acct_num");
        r.updt_ts = rs.getTimestamp("updt_ts");
        return r;
    }

    private static class TokenMapRow {
        String token_acct_num;
        String token_seq_num;
        String token_uniq_ref_id;
        String token_stat_cd;
        java.math.BigDecimal token_rqstr_id;
        String token_strg_type_cd;
        java.math.BigDecimal token_assrnc_lvl_num;
        String funding_acct_num;
        String fpan_expir_dt;
        String fpan_seq_num;
        String fincl_acct_id;
        java.math.BigDecimal cust_ica;
        String num_cntry_cd;
        String wlt_prvdr_id;
        String mbl_wlt_id;
        String mcbp_prcss_type_cd;
        String key_derivation_indx;
        String cryptgrm_ver_num;
        java.math.BigDecimal aip_list_id;
        java.math.BigDecimal prim_cvm_list_id;
        java.math.BigDecimal alt_cvm_list_id;
        java.math.BigDecimal dsbld_paymt_chnl;
        String hash_token_acct_num;
        Timestamp updt_ts;
    }
}
