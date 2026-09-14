package com.yugabyte.simulation.service.rtp;

import java.util.List;
import java.util.UUID;

public final class RtpPopulation {
    public final UUID homeLedgerEntityId;
    public final String geoPartition;
    public final UUID clearingAccountId;
    public final List<CustomerAccount> customerAccounts;
    public final List<ExternalAddress> externalAddresses;

    public RtpPopulation(UUID homeLedgerEntityId, String geoPartition, UUID clearingAccountId,
                         List<CustomerAccount> customerAccounts, List<ExternalAddress> externalAddresses) {
        this.homeLedgerEntityId = homeLedgerEntityId;
        this.geoPartition = geoPartition;
        this.clearingAccountId = clearingAccountId;
        this.customerAccounts = customerAccounts;
        this.externalAddresses = externalAddresses;
    }

    public static final class CustomerAccount {
        public final UUID accountId;
        public final UUID partyId;
        public final String currencyCode;
        public final String geoPartition;

        public CustomerAccount(UUID accountId, UUID partyId, String currencyCode, String geoPartition) {
            this.accountId = accountId;
            this.partyId = partyId;
            this.currencyCode = currencyCode;
            this.geoPartition = geoPartition;
        }
    }

    public static final class ExternalAddress {
        public final UUID partyId;
        public final UUID paymentAddressId;
        public final String rail;
        public final String geoPartition;

        public ExternalAddress(UUID partyId, UUID paymentAddressId, String rail, String geoPartition) {
            this.partyId = partyId;
            this.paymentAddressId = paymentAddressId;
            this.rail = rail;
            this.geoPartition = geoPartition;
        }
    }
}
