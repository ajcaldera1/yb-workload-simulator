WITH ins_parties AS (
    INSERT INTO rtp.parties (
        party_id, geo_partition, party_type, legal_entity_id, display_name,
        tax_id_token, customer_reference, email, phone_e164, is_active,
        created_at, updated_at
    )
    SELECT party_id, geo_partition, party_type::rtp.party_type, legal_entity_id,
           display_name, tax_id_token, customer_reference, email, phone_e164,
           is_active, created_at, updated_at
      FROM jsonb_to_recordset(?::jsonb) AS x(
            party_id uuid, geo_partition text, party_type text, legal_entity_id uuid,
            display_name text, tax_id_token text, customer_reference text, email text,
            phone_e164 text, is_active boolean, created_at timestamptz, updated_at timestamptz)
)
INSERT INTO rtp.accounts (
    account_id, geo_partition, owning_party_id, ledger_entity_id, account_type,
    currency_code, account_number_token, account_suffix, status, available_balance,
    current_balance, overdraft_limit, opened_at
)
SELECT account_id, geo_partition, owning_party_id, ledger_entity_id,
       account_type::rtp.account_type, currency_code, account_number_token,
       account_suffix, status::rtp.account_status, available_balance,
       current_balance, overdraft_limit, opened_at
  FROM jsonb_to_recordset(?::jsonb) AS x(
        account_id uuid, geo_partition text, owning_party_id uuid, ledger_entity_id uuid,
        account_type text, currency_code text, account_number_token text,
        account_suffix text, status text, available_balance numeric, current_balance numeric,
        overdraft_limit numeric, opened_at timestamptz)
