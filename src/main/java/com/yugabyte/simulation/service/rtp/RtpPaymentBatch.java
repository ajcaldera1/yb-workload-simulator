package com.yugabyte.simulation.service.rtp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates one in-flight batch of related payment rows for the CTE insert.
 */
public final class RtpPaymentBatch {
    public final List<Map<String, Object>> idempotencyKeys = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> payments = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> paymentEvents = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> accountReservations = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> ledgerTransactions = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> ledgerPostings = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> screeningResults = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> paymentReviews = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> paymentReturns = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> outboxEvents = new ArrayList<Map<String, Object>>();
    public final List<Map<String, Object>> auditLog = new ArrayList<Map<String, Object>>();
    public int paymentCount;

    public static Map<String, Object> row(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("row() requires even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
