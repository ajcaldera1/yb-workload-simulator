package com.yugabyte.simulation.service.rtp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import com.yugabyte.simulation.service.LoadGeneratorUtils;

/**
 * Ports the rtp_demo payment lifecycle factory: weighted rails/outcomes, event
 * chain, optional screening, reservation, balanced ledger, return, outbox, audit.
 */
public final class RtpPaymentFactory {

    static final String[] PURPOSE_CODES = {"GDS", "SALA", "SUPP", "UTIL", "RENT", "TRAD", "INTC", "OTHR"};
    static final String[] RETURN_REASONS = {"AC04", "AM04", "MS03", "AG01", "FRAD", "RR04", "CUST"};
    static final String[] REJECT_REASONS = {"AML_HOLD", "SANCTIONS_HIT", "INVALID_ACCOUNT", "INSUFFICIENT_FUNDS", "FORMAT_ERROR"};
    static final String[] NETWORK_FAIL_REASONS = {"NETWORK_TIMEOUT", "RECEIVING_BANK_REJECT", "ACCOUNT_CLOSED", "LIMIT_EXCEEDED"};
    static final String[] SCREENING_TYPES = {"AML", "SANCTIONS", "FRAUD"};
    static final String[] SCREENING_PROVIDERS = {"ComplyAdvantage", "Sardine", "Feedzai", "InHouseEngine"};
    static final String[] ANALYSTS = {"analyst.jkim", "analyst.mtorres", "analyst.rwong"};
    static final String[] RETURN_REQUESTERS = {"ops.analyst", "customer_service", "network_return"};
    static final String[] BUSINESS_SUFFIXES = {"LLC", "Inc", "Corp", "Partners"};
    static final String[] ADDRESS_TYPES = {"ALIAS_EMAIL", "ALIAS_PHONE", "ACCOUNT_ROUTING"};
    static final String[] NETWORK_RAILS = {"RTP", "FEDNOW"};
    static final BigDecimal[] OVERDRAFTS = {
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("500.00"), new BigDecimal("1000.00")
    };

    private static final BigDecimal CENTS = new BigDecimal("0.01");

    private final RtpPopulation population;
    private final Random rng;

    public RtpPaymentFactory(RtpPopulation population, Random rng) {
        this.population = population;
        this.rng = rng;
    }

    public void buildOne(RtpPaymentBatch batch, OffsetDateTime now) {
        String geo = population.geoPartition;
        String rail = weightedChoice(new String[]{"RTP", "FEDNOW", "INTERNAL"}, new int[]{45, 35, 20});
        UUID paymentId = UUID.randomUUID();
        BigDecimal amount = randomAmount();
        String currency = "USD";
        String e2e = "E2E" + hexUpper(24);
        String instr = "INS" + hexUpper(24);
        OffsetDateTime receivedAt = now.minus(Duration.ofMillis(inclusive(0, 4000)));

        UUID debtorAccountId;
        UUID debtorPartyId;
        UUID debtorAddressId;
        UUID creditorAccountId;
        UUID creditorPartyId;
        UUID creditorAddressId;
        String direction;

        if ("INTERNAL".equals(rail)) {
            direction = "OUTBOUND";
            RtpPopulation.CustomerAccount debtor = pickCustomer();
            RtpPopulation.CustomerAccount creditor = pickCustomer();
            while (creditor.accountId.equals(debtor.accountId) && population.customerAccounts.size() > 1) {
                creditor = pickCustomer();
            }
            debtorAccountId = debtor.accountId;
            debtorPartyId = debtor.partyId;
            debtorAddressId = null;
            creditorAccountId = creditor.accountId;
            creditorPartyId = creditor.partyId;
            creditorAddressId = null;
        } else {
            direction = weightedChoice(new String[]{"OUTBOUND", "INBOUND"}, new int[]{50, 50});
            RtpPopulation.CustomerAccount cust = pickCustomer();
            RtpPopulation.ExternalAddress ext = pickExternal(rail);
            if ("OUTBOUND".equals(direction)) {
                debtorAccountId = cust.accountId;
                debtorPartyId = cust.partyId;
                debtorAddressId = null;
                creditorAccountId = null;
                creditorPartyId = ext.partyId;
                creditorAddressId = ext.paymentAddressId;
            } else {
                debtorAccountId = null;
                debtorPartyId = ext.partyId;
                debtorAddressId = ext.paymentAddressId;
                creditorAccountId = cust.accountId;
                creditorPartyId = cust.partyId;
                creditorAddressId = null;
            }
        }

        String outcome = weightedChoice(
                new String[]{"SETTLED", "REJECTED", "RETURNED", "FAILED"},
                new int[]{90, 5, 3, 2});

        List<Map<String, Object>> events = new ArrayList<>();
        int[] seq = {1};

        OffsetDateTime t = receivedAt;
        addEvent(events, seq, paymentId, geo, "RECEIVED", null, "RECEIVED", t, "SYSTEM", RtpPaymentBatch.row());
        t = t.plus(Duration.ofMillis(inclusive(20, 150)));
        addEvent(events, seq, paymentId, geo, "VALIDATION_STARTED", "RECEIVED", "VALIDATING", t, "SYSTEM", RtpPaymentBatch.row());
        t = t.plus(Duration.ofMillis(inclusive(20, 200)));

        String networkPaymentId = "NET" + hexUpper(20);
        OffsetDateTime acceptedAt = null;
        OffsetDateTime settledAt = null;
        String statusReasonCode = null;
        String statusReasonDetail = null;
        Map<String, Object> reservationRow = null;
        List<Map<String, Object>> reviewRows = new ArrayList<>();
        List<Map<String, Object>> screeningRows = new ArrayList<>();

        if (rng.nextDouble() < 0.30) {
            for (String stype : sample(SCREENING_TYPES, inclusive(1, 2))) {
                String decision = weightedChoice(new String[]{"PASS", "REVIEW", "FAIL"}, new int[]{95, 4, 1});
                double riskCap = "PASS".equals(decision) ? 0.15 : 0.9;
                screeningRows.add(RtpPaymentBatch.row(
                        "screening_result_id", UUID.randomUUID().toString(),
                        "geo_partition", geo,
                        "payment_id", paymentId.toString(),
                        "screening_type", stype,
                        "provider", pick(SCREENING_PROVIDERS),
                        "provider_reference", "SCR-" + hexLower(12),
                        "decision", decision,
                        "risk_score", round4(rng.nextDouble() * riskCap),
                        "rules_version", "rules-2026.03",
                        "screened_at", iso(t),
                        "raw_result", RtpPaymentBatch.row("rail", rail)
                ));
                if (!"PASS".equals(decision)) {
                    reviewRows.add(RtpPaymentBatch.row(
                            "review_id", UUID.randomUUID().toString(),
                            "geo_partition", geo,
                            "payment_id", paymentId.toString(),
                            "review_type", stype,
                            "status", "CLEARED",
                            "priority", inclusive(40, 90),
                            "assigned_to", pick(ANALYSTS),
                            "opened_at", iso(t.plus(Duration.ofMinutes(inclusive(2, 40)))),
                            "resolved_at", iso(t.plus(Duration.ofMinutes(inclusive(41, 90)))),
                            "resolution_code", "REVIEWED_NO_ACTION",
                            "resolution_notes", "Reviewed against policy; no adverse finding."
                    ));
                }
            }
        }

        String finalStatus;
        if ("REJECTED".equals(outcome)) {
            statusReasonCode = pick(REJECT_REASONS);
            statusReasonDetail = "Rejected during validation: " + statusReasonCode;
            addEvent(events, seq, paymentId, geo, "VALIDATION_FAILED", "VALIDATING", "REJECTED", t, "SYSTEM",
                    RtpPaymentBatch.row("reason", statusReasonCode));
            finalStatus = "REJECTED";
        } else {
            addEvent(events, seq, paymentId, geo, "VALIDATION_PASSED", "VALIDATING", "APPROVED", t, "SYSTEM", RtpPaymentBatch.row());
            t = t.plus(Duration.ofMillis(inclusive(10, 100)));
            addEvent(events, seq, paymentId, geo, "APPROVED", "APPROVED", "APPROVED", t,
                    reviewRows.isEmpty() ? "SYSTEM" : "ANALYST", RtpPaymentBatch.row());

            if ("OUTBOUND".equals(direction) || "INTERNAL".equals(rail)) {
                reservationRow = RtpPaymentBatch.row(
                        "reservation_id", UUID.randomUUID().toString(),
                        "geo_partition", geo,
                        "account_id", debtorAccountId.toString(),
                        "payment_id", paymentId.toString(),
                        "amount", amount.toPlainString(),
                        "currency_code", currency,
                        "status", "ACTIVE",
                        "created_at", iso(t),
                        "released_at", null,
                        "expires_at", iso(t.plus(Duration.ofHours(1)))
                );
                addEvent(events, seq, paymentId, geo, "RESERVATION_CREATED", "APPROVED", "APPROVED", t, "SYSTEM", RtpPaymentBatch.row());
            }

            t = t.plus(Duration.ofMillis(inclusive(50, 300)));
            addEvent(events, seq, paymentId, geo, "SUBMITTED_TO_NETWORK", "APPROVED", "SUBMITTED", t, "SYSTEM", RtpPaymentBatch.row());
            t = t.plus(Duration.ofMillis(inclusive(100, 1500)));

            if ("FAILED".equals(outcome)) {
                statusReasonCode = pick(NETWORK_FAIL_REASONS);
                statusReasonDetail = "Network rejected payment: " + statusReasonCode;
                addEvent(events, seq, paymentId, geo, "NETWORK_REJECTED", "SUBMITTED", "FAILED", t, "SYSTEM",
                        RtpPaymentBatch.row("reason", statusReasonCode));
                if (reservationRow != null) {
                    reservationRow.put("status", "RELEASED");
                    reservationRow.put("released_at", iso(t));
                }
                finalStatus = "FAILED";
            } else {
                acceptedAt = t;
                addEvent(events, seq, paymentId, geo, "NETWORK_ACCEPTED", "SUBMITTED", "ACCEPTED", t, "SYSTEM", RtpPaymentBatch.row());
                t = t.plus(Duration.ofMillis(inclusive(20, 200)));
                settledAt = t;
                addEvent(events, seq, paymentId, geo, "SETTLED", "ACCEPTED", "SETTLED", t, "SYSTEM", RtpPaymentBatch.row());
                if (reservationRow != null) {
                    reservationRow.put("status", "CONSUMED");
                    reservationRow.put("released_at", iso(t));
                }
                finalStatus = "SETTLED";

                UUID ltId = UUID.randomUUID();
                batch.ledgerTransactions.add(RtpPaymentBatch.row(
                        "ledger_transaction_id", ltId.toString(),
                        "geo_partition", geo,
                        "payment_id", paymentId.toString(),
                        "entry_type", "PAYMENT_SETTLEMENT",
                        "effective_at", iso(t),
                        "posted_at", iso(t),
                        "external_reference", networkPaymentId,
                        "description", rail + " " + direction.toLowerCase() + " payment settlement",
                        "correlation_id", UUID.randomUUID().toString(),
                        "reversal_of_id", null,
                        "created_by", "rtp_workload",
                        "metadata", RtpPaymentBatch.row("rail", rail, "end_to_end_id", e2e)
                ));

                UUID debitAcct;
                UUID creditAcct;
                if ("INTERNAL".equals(rail)) {
                    debitAcct = debtorAccountId;
                    creditAcct = creditorAccountId;
                } else if ("OUTBOUND".equals(direction)) {
                    debitAcct = debtorAccountId;
                    creditAcct = population.clearingAccountId;
                } else {
                    debitAcct = population.clearingAccountId;
                    creditAcct = creditorAccountId;
                }
                batch.ledgerPostings.add(posting(geo, ltId, debitAcct, "DEBIT", amount, currency, t));
                batch.ledgerPostings.add(posting(geo, ltId, creditAcct, "CREDIT", amount, currency, t));

                if ("RETURNED".equals(outcome)) {
                    t = t.plus(Duration.ofMinutes(inclusive(5, 600)));
                    addEvent(events, seq, paymentId, geo, "RETURN_REQUESTED", "SETTLED", "RETURN_REQUESTED", t, "SYSTEM", RtpPaymentBatch.row());
                    t = t.plus(Duration.ofMinutes(inclusive(1, 60)));
                    addEvent(events, seq, paymentId, geo, "RETURNED", "RETURN_REQUESTED", "RETURNED", t, "SYSTEM", RtpPaymentBatch.row());
                    finalStatus = "RETURNED";

                    UUID rtId = UUID.randomUUID();
                    batch.ledgerTransactions.add(RtpPaymentBatch.row(
                            "ledger_transaction_id", rtId.toString(),
                            "geo_partition", geo,
                            "payment_id", paymentId.toString(),
                            "entry_type", "PAYMENT_RETURN",
                            "effective_at", iso(t),
                            "posted_at", iso(t),
                            "external_reference", "RTN-" + networkPaymentId,
                            "description", "Reversal of " + rail + " " + direction.toLowerCase() + " payment",
                            "correlation_id", UUID.randomUUID().toString(),
                            "reversal_of_id", ltId.toString(),
                            "created_by", "rtp_workload",
                            "metadata", RtpPaymentBatch.row("rail", rail, "original_ledger_transaction_id", ltId.toString())
                    ));
                    batch.ledgerPostings.add(posting(geo, rtId, creditAcct, "DEBIT", amount, currency, t));
                    batch.ledgerPostings.add(posting(geo, rtId, debitAcct, "CREDIT", amount, currency, t));
                    batch.paymentReturns.add(RtpPaymentBatch.row(
                            "return_id", UUID.randomUUID().toString(),
                            "geo_partition", geo,
                            "original_payment_id", paymentId.toString(),
                            "return_payment_id", null,
                            "return_reason_code", pick(RETURN_REASONS),
                            "requested_by", pick(RETURN_REQUESTERS),
                            "requested_at", iso(t),
                            "accepted_at", iso(t),
                            "settled_at", iso(t),
                            "notes", "Auto-generated synthetic return for load testing."
                    ));
                }
            }
        }

        UUID idemId = UUID.randomUUID();
        String clientId = "client-" + String.format("%04d", inclusive(1, 500));
        batch.idempotencyKeys.add(RtpPaymentBatch.row(
                "idempotency_key_id", idemId.toString(),
                "geo_partition", geo,
                "client_id", clientId,
                "idempotency_key", "idem-" + hexLower(32),
                "request_hash_hex", hexLower(64),
                "payment_id", paymentId.toString(),
                "response_code", 201,
                "response_body", RtpPaymentBatch.row("status", "accepted"),
                "first_seen_at", iso(receivedAt),
                "expires_at", iso(receivedAt.plus(Duration.ofDays(1)))
        ));

        Map<String, Object> payment = RtpPaymentBatch.row(
                "payment_id", paymentId.toString(),
                "geo_partition", geo,
                "network_payment_id", networkPaymentId,
                "end_to_end_id", e2e,
                "instruction_id", instr,
                "client_id", "client-" + String.format("%04d", inclusive(1, 500)),
                "idempotency_key_id", idemId.toString(),
                "rail", rail,
                "direction", direction,
                "status", finalStatus,
                "status_reason_code", statusReasonCode,
                "status_reason_detail", statusReasonDetail,
                "debtor_party_id", str(debtorPartyId),
                "debtor_account_id", str(debtorAccountId),
                "debtor_address_id", str(debtorAddressId),
                "creditor_party_id", str(creditorPartyId),
                "creditor_account_id", str(creditorAccountId),
                "creditor_address_id", str(creditorAddressId),
                "amount", amount.toPlainString(),
                "currency_code", currency,
                "requested_execution_at", null,
                "received_at", iso(receivedAt),
                "accepted_at", acceptedAt == null ? null : iso(acceptedAt),
                "settled_at", settledAt == null ? null : iso(settledAt),
                "expires_at", null,
                "remittance_information", LoadGeneratorUtils.getText(20, 60),
                "purpose_code", pick(PURPOSE_CODES),
                "structured_remittance", RtpPaymentBatch.row(),
                "external_metadata", RtpPaymentBatch.row("generator", "rtp_workload"),
                "version", 0,
                "last_event_sequence", seq[0] - 1
        );
        batch.payments.add(payment);
        batch.paymentEvents.addAll(events);
        if (reservationRow != null) {
            batch.accountReservations.add(reservationRow);
        }
        batch.screeningResults.addAll(screeningRows);
        batch.paymentReviews.addAll(reviewRows);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("payment_id", paymentId.toString());
        payload.put("geo_partition", geo);
        payload.put("status", finalStatus);
        payload.put("amount", amount.toPlainString());
        batch.outboxEvents.add(RtpPaymentBatch.row(
                "outbox_event_id", UUID.randomUUID().toString(),
                "geo_partition", geo,
                "aggregate_type", "payment",
                "aggregate_id", paymentId.toString(),
                "event_type", "payment." + finalStatus.toLowerCase(),
                "payload", payload,
                "headers", RtpPaymentBatch.row(),
                "occurred_at", iso(t),
                "published_at", null,
                "publish_attempts", 0,
                "last_error", null
        ));
        batch.auditLog.add(RtpPaymentBatch.row(
                "geo_partition", geo,
                "occurred_at", iso(receivedAt),
                "actor_type", "API",
                "actor_id", "client-" + String.format("%04d", inclusive(1, 500)),
                "action", "payment.create",
                "resource_type", "payment",
                "resource_id", paymentId.toString(),
                "correlation_id", UUID.randomUUID().toString(),
                "source_ip", randomIp(),
                "user_agent", "rtp_workload/1.0",
                "before_state", null,
                "after_state", RtpPaymentBatch.row("status", "RECEIVED"),
                "metadata", RtpPaymentBatch.row()
        ));
        batch.paymentCount++;
    }

    public static Map<String, Object> customerParty(UUID partyId, String geo, long index, boolean business) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return RtpPaymentBatch.row(
                "party_id", partyId.toString(),
                "geo_partition", geo,
                "party_type", business ? "BUSINESS" : "PERSON",
                "legal_entity_id", null,
                "display_name", displayName(business),
                "tax_id_token", null,
                "customer_reference", String.format("CUST-%08d", index),
                "email", "cust-" + partyId.toString().substring(0, 8) + "@example.com",
                "phone_e164", "+1" + LoadGeneratorUtils.getFixedLengthNumber(10),
                "is_active", true,
                "created_at", iso(now),
                "updated_at", iso(now)
        );
    }

    public static Map<String, Object> customerAccount(UUID accountId, UUID partyId, String geo, UUID ledgerEntityId,
                                                      Random rng) {
        boolean savings = rng.nextDouble() < 0.2;
        BigDecimal balance = money(Math.exp(rng.nextGaussian() * 1.3 + 8.5));
        return RtpPaymentBatch.row(
                "account_id", accountId.toString(),
                "geo_partition", geo,
                "owning_party_id", partyId.toString(),
                "ledger_entity_id", ledgerEntityId.toString(),
                "account_type", savings ? "SAVINGS" : "DDA",
                "currency_code", "USD",
                "account_number_token", "TOK" + hexUpper(18),
                "account_suffix", String.format("%04d", rng.nextInt(10000)),
                "status", "OPEN",
                "available_balance", balance.toPlainString(),
                "current_balance", balance.toPlainString(),
                "overdraft_limit", OVERDRAFTS[rng.nextInt(OVERDRAFTS.length)].toPlainString(),
                "opened_at", iso(OffsetDateTime.now(ZoneOffset.UTC))
        );
    }

    public static Map<String, Object> externalParty(UUID partyId, String geo, long index, boolean business) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return RtpPaymentBatch.row(
                "party_id", partyId.toString(),
                "geo_partition", geo,
                "party_type", business ? "BUSINESS" : "PERSON",
                "legal_entity_id", null,
                "display_name", displayName(business),
                "tax_id_token", null,
                "customer_reference", String.format("EXT-%08d", index),
                "email", "ext-" + partyId.toString().substring(0, 8) + "@example.com",
                "phone_e164", "+1" + LoadGeneratorUtils.getFixedLengthNumber(10),
                "is_active", true,
                "created_at", iso(now),
                "updated_at", iso(now)
        );
    }

    public static Map<String, Object> externalAddress(UUID partyId, String geo, Random rng) {
        String rail = NETWORK_RAILS[rng.nextInt(NETWORK_RAILS.length)];
        String addrType = ADDRESS_TYPES[rng.nextInt(ADDRESS_TYPES.length)];
        Map<String, Object> row = RtpPaymentBatch.row(
                "payment_address_id", UUID.randomUUID().toString(),
                "geo_partition", geo,
                "party_id", partyId.toString(),
                "account_id", null,
                "rail", rail,
                "address_type", addrType,
                "routing_token", null,
                "account_token", null,
                "alias_value", null,
                "is_verified", rng.nextDouble() < 0.9,
                "is_active", true,
                "valid_from", iso(OffsetDateTime.now(ZoneOffset.UTC))
        );
        if ("ACCOUNT_ROUTING".equals(addrType)) {
            row.put("routing_token", LoadGeneratorUtils.getFixedLengthNumber(9));
            row.put("account_token", "TOK" + hexUpper(16));
        } else if ("ALIAS_EMAIL".equals(addrType)) {
            row.put("alias_value", "alias-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com");
        } else {
            row.put("alias_value", "+1" + LoadGeneratorUtils.getFixedLengthNumber(10));
        }
        return row;
    }

    public static BigDecimal money(double value) {
        double bounded = Math.max(1.0, Math.min(value, 250_000.0));
        return BigDecimal.valueOf(bounded).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal randomAmount() {
        return money(Math.exp(rng.nextGaussian() * 1.15 + 4.8));
    }

    private RtpPopulation.CustomerAccount pickCustomer() {
        return population.customerAccounts.get(rng.nextInt(population.customerAccounts.size()));
    }

    private RtpPopulation.ExternalAddress pickExternal(String rail) {
        List<RtpPopulation.ExternalAddress> matches = new ArrayList<>();
        for (RtpPopulation.ExternalAddress ext : population.externalAddresses) {
            if (rail.equals(ext.rail)) {
                matches.add(ext);
            }
        }
        List<RtpPopulation.ExternalAddress> pool = matches.isEmpty() ? population.externalAddresses : matches;
        return pool.get(rng.nextInt(pool.size()));
    }

    private String weightedChoice(String[] values, int[] weights) {
        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int pick = rng.nextInt(total);
        int acc = 0;
        for (int i = 0; i < values.length; i++) {
            acc += weights[i];
            if (pick < acc) {
                return values[i];
            }
        }
        return values[values.length - 1];
    }

    private List<String> sample(String[] items, int k) {
        List<String> copy = new ArrayList<>();
        Collections.addAll(copy, items);
        Collections.shuffle(copy, rng);
        return copy.subList(0, Math.min(k, copy.size()));
    }

    private String pick(String[] values) {
        return values[rng.nextInt(values.length)];
    }

    private int inclusive(int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    private static void addEvent(List<Map<String, Object>> events, int[] seq, UUID paymentId, String geo,
                                 String eventType, String prior, String resulting, OffsetDateTime ts,
                                 String actor, Map<String, Object> detail) {
        events.add(RtpPaymentBatch.row(
                "payment_event_id", UUID.randomUUID().toString(),
                "geo_partition", geo,
                "payment_id", paymentId.toString(),
                "event_sequence", seq[0],
                "event_type", eventType,
                "prior_status", prior,
                "resulting_status", resulting,
                "actor_type", actor,
                "actor_id", null,
                "occurred_at", iso(ts),
                "correlation_id", UUID.randomUUID().toString(),
                "causation_id", null,
                "detail", detail
        ));
        seq[0]++;
    }

    private static Map<String, Object> posting(String geo, UUID ledgerTxnId, UUID accountId, String direction,
                                               BigDecimal amount, String currency, OffsetDateTime ts) {
        return RtpPaymentBatch.row(
                "ledger_posting_id", UUID.randomUUID().toString(),
                "geo_partition", geo,
                "ledger_transaction_id", ledgerTxnId.toString(),
                "account_id", accountId.toString(),
                "direction", direction,
                "amount", amount.toPlainString(),
                "currency_code", currency,
                "created_at", iso(ts)
        );
    }

    private static String displayName(boolean business) {
        String name = LoadGeneratorUtils.getName();
        if (business) {
            return name + " " + BUSINESS_SUFFIXES[Math.abs(name.hashCode()) % BUSINESS_SUFFIXES.length];
        }
        return name;
    }

    static String iso(OffsetDateTime ts) {
        return ts.withOffsetSameInstant(ZoneOffset.UTC).toString();
    }

    private static String str(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String hexUpper(int length) {
        return LoadGeneratorUtils.getHexString(length);
    }

    private static String hexLower(int length) {
        return LoadGeneratorUtils.getHexString(length).toLowerCase();
    }

    private String randomIp() {
        return inclusive(1, 223) + "." + rng.nextInt(256) + "." + rng.nextInt(256) + "." + inclusive(1, 254);
    }

    private static double round4(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
