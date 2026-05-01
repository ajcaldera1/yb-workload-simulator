package com.yugabyte.simulation.service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.workload.Step;
import com.yugabyte.simulation.workload.WorkloadSimulationBase;

/**
 * Variant of Token workload that uses a single CTE to: (1) read the current row from token_map,
 * (2) insert that old row into token_map_hist, (3) INSERT into token_map ON CONFLICT DO UPDATE.
 */
@Repository
public class TokenCteWorkload extends WorkloadSimulationBase implements WorkloadSimulation {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${SPRING_APPLICATION_NAME:}")
    private String applicationName;

    private static final int TOKEN_ACCT_NUM_LENGTH = 19;

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

    private static final String INSERT_TOKEN_MAP =
            "insert into token_map (token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_expir_dt, token_type_cd,"
                    + " token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num, funding_acct_num, fpan_expir_dt, fpan_seq_num,"
                    + " fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd, key_derivation_indx, cryptgrm_ver_num,"
                    + " aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, crte_ts, updt_ts, rplctn_updt_ts,"
                    + " hash_token_map_txt, hash_token_acct_num, hash_fund_acct_num)"
                    + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    // CTE: (1) read current row from token_map, (2) insert that row into token_map_hist, (3) upsert token_map
    // Params: 1 = token_acct_num for old_row; 2..31 = 30 column values for the INSERT
    private static final String CTE_READ_HIST_UPSERT =
            "WITH old_row AS ("
                    + " SELECT token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num,"
                    + "        funding_acct_num, fpan_expir_dt, fpan_seq_num, fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd,"
                    + "        key_derivation_indx, cryptgrm_ver_num, aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, hash_token_acct_num, updt_ts"
                    + " FROM token_map WHERE token_acct_num = ?"
                    + " ),"
                    + " hist_insert AS ("
                    + " INSERT INTO token_map_hist (token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num,"
                    + "   funding_acct_num, fpan_expir_dt, fpan_seq_num, fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd,"
                    + "   key_derivation_indx, cryptgrm_ver_num, aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, hash_token_acct_num, updt_epoch_ms_num)"
                    + " SELECT token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num,"
                    + "        funding_acct_num, fpan_expir_dt, fpan_seq_num, fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd,"
                    + "        key_derivation_indx, cryptgrm_ver_num, aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, hash_token_acct_num,"
                    + "        (extract(epoch from updt_ts) * 1000)::numeric(20,0)"
                    + " FROM old_row"
                    + " )"
                    + " INSERT INTO token_map (token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_expir_dt, token_type_cd,"
                    + "   token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num, funding_acct_num, fpan_expir_dt, fpan_seq_num,"
                    + "   fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd, key_derivation_indx, cryptgrm_ver_num,"
                    + "   aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl, crte_ts, updt_ts, rplctn_updt_ts,"
                    + "   hash_token_map_txt, hash_token_acct_num, hash_fund_acct_num)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (token_acct_num) DO UPDATE SET"
                    + "   token_seq_num = EXCLUDED.token_seq_num, token_uniq_ref_id = EXCLUDED.token_uniq_ref_id, token_stat_cd = EXCLUDED.token_stat_cd,"
                    + "   token_expir_dt = EXCLUDED.token_expir_dt, token_type_cd = EXCLUDED.token_type_cd, token_rqstr_id = EXCLUDED.token_rqstr_id,"
                    + "   token_strg_type_cd = EXCLUDED.token_strg_type_cd, token_assrnc_lvl_num = EXCLUDED.token_assrnc_lvl_num,"
                    + "   funding_acct_num = EXCLUDED.funding_acct_num, fpan_expir_dt = EXCLUDED.fpan_expir_dt, fpan_seq_num = EXCLUDED.fpan_seq_num,"
                    + "   fincl_acct_id = EXCLUDED.fincl_acct_id, cust_ica = EXCLUDED.cust_ica, num_cntry_cd = EXCLUDED.num_cntry_cd,"
                    + "   wlt_prvdr_id = EXCLUDED.wlt_prvdr_id, mbl_wlt_id = EXCLUDED.mbl_wlt_id, mcbp_prcss_type_cd = EXCLUDED.mcbp_prcss_type_cd,"
                    + "   key_derivation_indx = EXCLUDED.key_derivation_indx, cryptgrm_ver_num = EXCLUDED.cryptgrm_ver_num,"
                    + "   aip_list_id = EXCLUDED.aip_list_id, prim_cvm_list_id = EXCLUDED.prim_cvm_list_id, alt_cvm_list_id = EXCLUDED.alt_cvm_list_id,"
                    + "   dsbld_paymt_chnl = EXCLUDED.dsbld_paymt_chnl, crte_ts = EXCLUDED.crte_ts, updt_ts = EXCLUDED.updt_ts, rplctn_updt_ts = EXCLUDED.rplctn_updt_ts,"
                    + "   hash_token_map_txt = EXCLUDED.hash_token_map_txt, hash_token_acct_num = EXCLUDED.hash_token_acct_num, hash_fund_acct_num = EXCLUDED.hash_fund_acct_num";

    private enum WorkloadType {
        CREATE_TABLES,
        SEED_DATA,
        RUN_SIMULATION
    }

    @Override
    public String getName() {
        return "TOKEN_CTE" + ((applicationName != null && !applicationName.isEmpty()) ? " [" + applicationName + "]" : "");
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
                        "Run Simulation (CTE)",
                        "Single CTE: read token_map, insert old row into token_map_hist, upsert token_map",
                        new WorkloadParamDesc("Invocations", 1, Integer.MAX_VALUE, 100_000),
                        new WorkloadParamDesc("Seeded rows (max token id)", 1, Integer.MAX_VALUE, 10_000),
                        new WorkloadParamDesc("Threads", 1, 500, 32)
                )
                        .setDescription("One statement with CTE: (1) read current row from token_map, (2) insert that row into token_map_hist, (3) INSERT ... ON CONFLICT DO UPDATE on token_map. 50% existing / 50% new token_acct_num.")
                        .onInvoke((runner, params) -> {
                            jdbcTemplate.setFetchSize(1000);
                            int seededRows = params.asInt(1);
                            AtomicLong nextNewTokenId = new AtomicLong(seededRows + 1);
                            runner.newFixedTargetInstance()
                                    .setCustomData(nextNewTokenId)
                                    .execute(params.asInt(2), params.asInt(0),
                                            (customData, threadData) -> {
                                                runCteUpsert(seededRows, (AtomicLong) customData);
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

    private static String randomFutureDateYyyyMmDd() {
        LocalDate today = LocalDate.now();
        int daysFromNow = LoadGeneratorUtils.getInt(1, 10 * 365 + 1);
        LocalDate future = today.plusDays(daysFromNow);
        return String.format("%04d%02d%02d", future.getYear(), future.getMonthValue(), future.getDayOfMonth());
    }

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

    private void runCteUpsert(int seededRows, AtomicLong nextNewTokenId) {
        if (seededRows <= 0) return;
        String tokenAcctNum = ThreadLocalRandom.current().nextBoolean()
                ? padTokenAcctNum(ThreadLocalRandom.current().nextInt(1, seededRows + 1))
                : padTokenAcctNum(nextNewTokenId.getAndIncrement());

        Object[] newValues = generateNewTokenMapValues(tokenAcctNum);
        newValues[0] = tokenAcctNum;

        // Params: 1 = token_acct_num (for CTE old_row), then 30 column values for INSERT
        Object[] params = new Object[31];
        params[0] = tokenAcctNum;
        System.arraycopy(newValues, 0, params, 1, 30);

        jdbcTemplate.update(CTE_READ_HIST_UPSERT, params);
    }
}
