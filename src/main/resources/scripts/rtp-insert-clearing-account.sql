WITH ins_party AS (
    INSERT INTO rtp.parties (
        party_id, geo_partition, party_type, legal_entity_id, display_name,
        customer_reference, email, is_active
    )
    VALUES (gen_random_uuid(), ?, 'FINANCIAL_INSTITUTION', ?,
            ?, ?, ?, true)
    RETURNING party_id, geo_partition
)
INSERT INTO rtp.accounts (
    account_id, geo_partition, owning_party_id, ledger_entity_id, account_type,
    currency_code, account_number_token, account_suffix, status,
    available_balance, current_balance
)
SELECT gen_random_uuid(), ins_party.geo_partition, ins_party.party_id, ?,
       'INTERNAL_CLEARING', 'USD', ?, '0000', 'OPEN',
       100000000.00, 100000000.00
  FROM ins_party
RETURNING account_id
