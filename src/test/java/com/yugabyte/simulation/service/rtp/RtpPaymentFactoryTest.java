package com.yugabyte.simulation.service.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class RtpPaymentFactoryTest {

    @Test
    void buildsReferentialPaymentGraphWithBalancedLedger() throws Exception {
        RtpPopulation population = samplePopulation();
        RtpPaymentFactory factory = new RtpPaymentFactory(population, new Random(42));
        RtpPaymentBatch batch = new RtpPaymentBatch();
        OffsetDateTime now = OffsetDateTime.parse("2026-09-14T12:00:00Z");

        for (int i = 0; i < 200; i++) {
            factory.buildOne(batch, now);
        }

        assertEquals(200, batch.paymentCount);
        assertEquals(200, batch.payments.size());
        assertEquals(200, batch.idempotencyKeys.size());
        assertEquals(200, batch.outboxEvents.size());
        assertEquals(200, batch.auditLog.size());
        assertFalse(batch.paymentEvents.isEmpty());

        Map<String, Integer> outcomes = new HashMap<>();
        for (Map<String, Object> payment : batch.payments) {
            outcomes.merge((String) payment.get("status"), 1, Integer::sum);
            assertEquals("us-east-1", payment.get("geo_partition"));
            assertTrue(batch.paymentEvents.stream().anyMatch(event ->
                    payment.get("payment_id").equals(event.get("payment_id"))));
        }
        assertTrue(outcomes.getOrDefault("SETTLED", 0) + outcomes.getOrDefault("RETURNED", 0) > 150);
        assertTrue(outcomes.getOrDefault("REJECTED", 0) > 0);
        assertTrue(outcomes.containsKey("FAILED") || outcomes.getOrDefault("REJECTED", 0) >= 1);

        assertLedgerBalanced(batch);
        assertReturnedPaymentsHaveReversals(batch, outcomes.getOrDefault("RETURNED", 0));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(batch.payments);
        assertTrue(json.contains("\"rail\""));
        assertTrue(json.contains("\"end_to_end_id\""));
    }

    @Test
    void seedRowsUseCustomerAndExternalReferences() {
        UUID partyId = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();
        Map<String, Object> customer = RtpPaymentFactory.customerParty(partyId, "us-west-2", 7, false);
        Map<String, Object> account = RtpPaymentFactory.customerAccount(
                UUID.randomUUID(), partyId, "us-west-2", ledger, new Random(1));
        Map<String, Object> external = RtpPaymentFactory.externalParty(UUID.randomUUID(), "eu-west-1", 3, true);
        Map<String, Object> address = RtpPaymentFactory.externalAddress(partyId, "eu-west-1", new Random(2));

        assertEquals("CUST-00000007", customer.get("customer_reference"));
        assertEquals("us-west-2", customer.get("geo_partition"));
        assertEquals(partyId.toString(), account.get("owning_party_id"));
        assertEquals("EXT-00000003", external.get("customer_reference"));
        assertTrue("RTP".equals(address.get("rail")) || "FEDNOW".equals(address.get("rail")));
    }

    private static void assertLedgerBalanced(RtpPaymentBatch batch) {
        Map<String, BigDecimal> netByTxn = new HashMap<>();
        for (Map<String, Object> posting : batch.ledgerPostings) {
            String txnId = (String) posting.get("ledger_transaction_id");
            BigDecimal amount = new BigDecimal((String) posting.get("amount"));
            if ("CREDIT".equals(posting.get("direction"))) {
                amount = amount.negate();
            }
            netByTxn.merge(txnId, amount, BigDecimal::add);
        }
        for (BigDecimal net : netByTxn.values()) {
            assertEquals(0, net.compareTo(BigDecimal.ZERO), "ledger transaction was not balanced");
        }
        assertFalse(netByTxn.isEmpty());
    }

    private static void assertReturnedPaymentsHaveReversals(RtpPaymentBatch batch, int returnedCount) {
        assertEquals(returnedCount, batch.paymentReturns.size());
        Set<String> returnedIds = new HashSet<>();
        for (Map<String, Object> payment : batch.payments) {
            if ("RETURNED".equals(payment.get("status"))) {
                returnedIds.add((String) payment.get("payment_id"));
            }
        }
        for (String paymentId : returnedIds) {
            long txnCount = batch.ledgerTransactions.stream()
                    .filter(row -> paymentId.equals(row.get("payment_id")))
                    .count();
            assertEquals(2, txnCount);
            assertTrue(batch.ledgerTransactions.stream().anyMatch(row ->
                    paymentId.equals(row.get("payment_id")) && "PAYMENT_RETURN".equals(row.get("entry_type"))));
        }
    }

    private static RtpPopulation samplePopulation() {
        List<RtpPopulation.CustomerAccount> customers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            customers.add(new RtpPopulation.CustomerAccount(
                    UUID.randomUUID(), UUID.randomUUID(), "USD", "us-east-1"));
        }
        List<RtpPopulation.ExternalAddress> externals = new ArrayList<RtpPopulation.ExternalAddress>();
        externals.add(new RtpPopulation.ExternalAddress(UUID.randomUUID(), UUID.randomUUID(), "RTP", "us-east-1"));
        externals.add(new RtpPopulation.ExternalAddress(UUID.randomUUID(), UUID.randomUUID(), "FEDNOW", "us-east-1"));
        return new RtpPopulation(
                UUID.randomUUID(),
                "us-east-1",
                UUID.randomUUID(),
                customers,
                externals);
    }
}
