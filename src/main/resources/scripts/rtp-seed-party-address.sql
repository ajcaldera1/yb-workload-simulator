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
INSERT INTO rtp.payment_addresses (
    payment_address_id, geo_partition, party_id, account_id, rail, address_type,
    routing_token, account_token, alias_value, is_verified, is_active, valid_from
)
SELECT payment_address_id, geo_partition, party_id, account_id, rail::rtp.payment_rail,
       address_type, routing_token, account_token, alias_value, is_verified,
       is_active, valid_from
  FROM jsonb_to_recordset(?::jsonb) AS x(
        payment_address_id uuid, geo_partition text, party_id uuid, account_id uuid,
        rail text, address_type text, routing_token text, account_token text,
        alias_value text, is_verified boolean, is_active boolean, valid_from timestamptz)
