package com.yugabyte.simulation.service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.yugabyte.simulation.dao.WorkloadDesc;
import com.yugabyte.simulation.dao.WorkloadParamDesc;
import com.yugabyte.simulation.workload.WorkloadSimulationBase;

/**
 * Read-only variant of Token workload. Only reads from token_map by token_acct_num.
 * All token_acct_nums are from the seeded range [1, seededRows]. Assumes token_map
 * has been created and seeded (e.g. by TOKEN_DEMO) before running.
 */
@Repository
public class TokenReadOnlyWorkload extends WorkloadSimulationBase implements WorkloadSimulation {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${SPRING_APPLICATION_NAME:}")
    private String applicationName;

    private static final int TOKEN_ACCT_NUM_LENGTH = 19;

    private static final String SELECT_TOKEN_MAP =
            "select token_acct_num, token_seq_num, token_uniq_ref_id, token_stat_cd, token_expir_dt, token_type_cd,"
                    + " token_rqstr_id, token_strg_type_cd, token_assrnc_lvl_num, funding_acct_num, fpan_expir_dt, fpan_seq_num,"
                    + " fincl_acct_id, cust_ica, num_cntry_cd, wlt_prvdr_id, mbl_wlt_id, mcbp_prcss_type_cd,"
                    + " key_derivation_indx, cryptgrm_ver_num, aip_list_id, prim_cvm_list_id, alt_cvm_list_id, dsbld_paymt_chnl,"
                    + " crte_ts, updt_ts, rplctn_updt_ts, hash_token_map_txt, hash_token_acct_num, hash_fund_acct_num, hash_fincl_acct_id"
                    + " from token_map where token_acct_num = ?";

    private enum WorkloadType {
        RUN_READ_ONLY
    }

    @Override
    public String getName() {
        return "TOKEN_READ_ONLY" + ((applicationName != null && !applicationName.isEmpty()) ? " [" + applicationName + "]" : "");
    }

    @Override
    public List<WorkloadDesc> getWorkloads() {
        return Arrays.asList(
                new WorkloadDesc(
                        WorkloadType.RUN_READ_ONLY.toString(),
                        "Run Read-Only",
                        "Read from token_map by token_acct_num; all keys from seeded range",
                        new WorkloadParamDesc("Invocations", 1, Integer.MAX_VALUE, 100_000),
                        new WorkloadParamDesc("Seeded rows (max token id)", 1, Integer.MAX_VALUE, 10_000),
                        new WorkloadParamDesc("Threads", 1, 500, 32)
                )
                        .setDescription("Point reads from token_map. Each invocation picks a random token_acct_num in [1, seeded rows] and SELECTs that row. No writes.")
                        .onInvoke((runner, params) -> {
                            jdbcTemplate.setFetchSize(1000);
                            int seededRows = params.asInt(1);
                            runner.newFixedTargetInstance()
                                    .execute(params.asInt(2), params.asInt(0),
                                            (customData, threadData) -> {
                                                readTokenMapByAcctNum(seededRows);
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

    private void readTokenMapByAcctNum(int seededRows) {
        if (seededRows <= 0) return;
        int id = ThreadLocalRandom.current().nextInt(1, seededRows + 1);
        String tokenAcctNum = padTokenAcctNum(id);
        jdbcTemplate.query(SELECT_TOKEN_MAP, new Object[]{tokenAcctNum}, rs -> {});
    }
}
