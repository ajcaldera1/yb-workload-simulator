WITH
idem AS (
    INSERT INTO rtp.idempotency_keys (
        idempotency_key_id, geo_partition, client_id, idempotency_key, request_hash,
        payment_id, response_code, response_body, first_seen_at, expires_at
    )
    SELECT idempotency_key_id, geo_partition, client_id, idempotency_key,
           decode(request_hash_hex, 'hex'), payment_id, response_code,
           response_body, first_seen_at, expires_at
      FROM jsonb_to_recordset(?::jsonb) AS x(
            idempotency_key_id uuid, geo_partition text, client_id text,
            idempotency_key text, request_hash_hex text, payment_id uuid,
            response_code integer, response_body jsonb, first_seen_at timestamptz,
            expires_at timestamptz)
),
pay AS (
    INSERT INTO rtp.payments (
        payment_id, geo_partition, network_payment_id, end_to_end_id, instruction_id,
        client_id, idempotency_key_id, rail, direction, status, status_reason_code,
        status_reason_detail, debtor_party_id, debtor_account_id, debtor_address_id,
        creditor_party_id, creditor_account_id, creditor_address_id, amount,
        currency_code, requested_execution_at, received_at, accepted_at, settled_at,
        expires_at, remittance_information, purpose_code, structured_remittance,
        external_metadata, version, last_event_sequence
    )
    SELECT payment_id, geo_partition, network_payment_id, end_to_end_id, instruction_id,
           client_id, idempotency_key_id, rail::rtp.payment_rail,
           direction::rtp.payment_direction, status::rtp.payment_status,
           status_reason_code, status_reason_detail, debtor_party_id, debtor_account_id,
           debtor_address_id, creditor_party_id, creditor_account_id, creditor_address_id,
           amount, currency_code, requested_execution_at, received_at, accepted_at,
           settled_at, expires_at, remittance_information, purpose_code,
           structured_remittance, external_metadata, version, last_event_sequence
      FROM jsonb_to_recordset(?::jsonb) AS x(
            payment_id uuid, geo_partition text, network_payment_id text,
            end_to_end_id text, instruction_id text, client_id text,
            idempotency_key_id uuid, rail text, direction text, status text,
            status_reason_code text, status_reason_detail text, debtor_party_id uuid,
            debtor_account_id uuid, debtor_address_id uuid, creditor_party_id uuid,
            creditor_account_id uuid, creditor_address_id uuid, amount numeric,
            currency_code text, requested_execution_at timestamptz, received_at timestamptz,
            accepted_at timestamptz, settled_at timestamptz, expires_at timestamptz,
            remittance_information text, purpose_code text, structured_remittance jsonb,
            external_metadata jsonb, version bigint, last_event_sequence integer)
),
ev AS (
    INSERT INTO rtp.payment_events (
        payment_event_id, geo_partition, payment_id, event_sequence, event_type,
        prior_status, resulting_status, actor_type, actor_id, occurred_at,
        correlation_id, causation_id, detail
    )
    SELECT payment_event_id, geo_partition, payment_id, event_sequence,
           event_type::rtp.event_type, prior_status::rtp.payment_status,
           resulting_status::rtp.payment_status, actor_type, actor_id, occurred_at,
           correlation_id, causation_id, detail
      FROM jsonb_to_recordset(?::jsonb) AS x(
            payment_event_id uuid, geo_partition text, payment_id uuid,
            event_sequence integer, event_type text, prior_status text,
            resulting_status text, actor_type text, actor_id text,
            occurred_at timestamptz, correlation_id uuid, causation_id uuid, detail jsonb)
),
rsv AS (
    INSERT INTO rtp.account_reservations (
        reservation_id, geo_partition, account_id, payment_id, amount, currency_code,
        status, created_at, released_at, expires_at
    )
    SELECT reservation_id, geo_partition, account_id, payment_id, amount, currency_code,
           status, created_at, released_at, expires_at
      FROM jsonb_to_recordset(?::jsonb) AS x(
            reservation_id uuid, geo_partition text, account_id uuid, payment_id uuid,
            amount numeric, currency_code text, status text, created_at timestamptz,
            released_at timestamptz, expires_at timestamptz)
),
lt AS (
    INSERT INTO rtp.ledger_transactions (
        ledger_transaction_id, geo_partition, payment_id, entry_type, effective_at,
        posted_at, external_reference, description, correlation_id, reversal_of_id,
        created_by, metadata
    )
    SELECT ledger_transaction_id, geo_partition, payment_id, entry_type::rtp.ledger_entry_type,
           effective_at, posted_at, external_reference, description, correlation_id,
           reversal_of_id, created_by, metadata
      FROM jsonb_to_recordset(?::jsonb) AS x(
            ledger_transaction_id uuid, geo_partition text, payment_id uuid, entry_type text,
            effective_at timestamptz, posted_at timestamptz, external_reference text,
            description text, correlation_id uuid, reversal_of_id uuid, created_by text,
            metadata jsonb)
),
lp AS (
    INSERT INTO rtp.ledger_postings (
        ledger_posting_id, geo_partition, ledger_transaction_id, account_id, direction,
        amount, currency_code, created_at
    )
    SELECT ledger_posting_id, geo_partition, ledger_transaction_id, account_id,
           direction::rtp.debit_credit, amount, currency_code, created_at
      FROM jsonb_to_recordset(?::jsonb) AS x(
            ledger_posting_id uuid, geo_partition text, ledger_transaction_id uuid,
            account_id uuid, direction text, amount numeric, currency_code text,
            created_at timestamptz)
),
scr AS (
    INSERT INTO rtp.screening_results (
        screening_result_id, geo_partition, payment_id, screening_type, provider,
        provider_reference, decision, risk_score, rules_version, screened_at, raw_result
    )
    SELECT screening_result_id, geo_partition, payment_id, screening_type::rtp.review_type,
           provider, provider_reference, decision, risk_score, rules_version,
           screened_at, raw_result
      FROM jsonb_to_recordset(?::jsonb) AS x(
            screening_result_id uuid, geo_partition text, payment_id uuid, screening_type text,
            provider text, provider_reference text, decision text, risk_score numeric,
            rules_version text, screened_at timestamptz, raw_result jsonb)
),
rev AS (
    INSERT INTO rtp.payment_reviews (
        review_id, geo_partition, payment_id, review_type, status, priority, assigned_to,
        opened_at, resolved_at, resolution_code, resolution_notes
    )
    SELECT review_id, geo_partition, payment_id, review_type::rtp.review_type,
           status::rtp.review_status, priority, assigned_to, opened_at, resolved_at,
           resolution_code, resolution_notes
      FROM jsonb_to_recordset(?::jsonb) AS x(
            review_id uuid, geo_partition text, payment_id uuid, review_type text,
            status text, priority smallint, assigned_to text, opened_at timestamptz,
            resolved_at timestamptz, resolution_code text, resolution_notes text)
),
ret AS (
    INSERT INTO rtp.payment_returns (
        return_id, geo_partition, original_payment_id, return_payment_id, return_reason_code,
        requested_by, requested_at, accepted_at, settled_at, notes
    )
    SELECT return_id, geo_partition, original_payment_id, return_payment_id, return_reason_code,
           requested_by, requested_at, accepted_at, settled_at, notes
      FROM jsonb_to_recordset(?::jsonb) AS x(
            return_id uuid, geo_partition text, original_payment_id uuid,
            return_payment_id uuid, return_reason_code text, requested_by text,
            requested_at timestamptz, accepted_at timestamptz, settled_at timestamptz,
            notes text)
),
ob AS (
    INSERT INTO rtp.outbox_events (
        outbox_event_id, geo_partition, aggregate_type, aggregate_id, event_type,
        payload, headers, occurred_at, published_at, publish_attempts, last_error
    )
    SELECT outbox_event_id, geo_partition, aggregate_type, aggregate_id, event_type,
           payload, headers, occurred_at, published_at, publish_attempts, last_error
      FROM jsonb_to_recordset(?::jsonb) AS x(
            outbox_event_id uuid, geo_partition text, aggregate_type text, aggregate_id uuid,
            event_type text, payload jsonb, headers jsonb, occurred_at timestamptz,
            published_at timestamptz, publish_attempts integer, last_error text)
)
INSERT INTO rtp.audit_log (
    geo_partition, occurred_at, actor_type, actor_id, action, resource_type, resource_id,
    correlation_id, source_ip, user_agent, before_state, after_state, metadata
)
SELECT geo_partition, occurred_at, actor_type, actor_id, action, resource_type, resource_id,
       correlation_id, source_ip::inet, user_agent, before_state, after_state, metadata
  FROM jsonb_to_recordset(?::jsonb) AS x(
        geo_partition text, occurred_at timestamptz, actor_type text, actor_id text,
        action text, resource_type text, resource_id text, correlation_id uuid,
        source_ip text, user_agent text, before_state jsonb, after_state jsonb, metadata jsonb)
