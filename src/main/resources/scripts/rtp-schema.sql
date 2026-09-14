-- Real-time payment (RTP) schema for YugabyteDB YSQL (PostgreSQL-compatible).
-- Adapted from rtp_demo for yb-workload-simulator (RtpWorkload).
-- Designed for a multi-region universe: hash-sharded keys, row-level
-- geo-partitioning, covering/partial indexes, and single-statement CTE
-- write paths (payment transition + outbox in one round trip).
--
-- This is an illustrative logical/physical schema, not a complete payment-network
-- implementation. Production implementations must be aligned to the institution's
-- BSA/AML, sanctions, PCI, privacy, retention, encryption, auditing, and applicable
-- network-rule obligations.
--
-- Multi-region principles:
--   * geo_partition is part of every hot-path primary key so rows pin to a region.
--   * Default geo_partition = yb_server_region() so local inserts need no extra hop.
--   * Related payment rows (events, outbox, ledger, holds) share the payment's
--     geo_partition so a transaction does not span regions.
--   * HASH on the natural id distributes writes; ASC keys are only used after a
--     hash/bucket prefix so monotonic timestamps do not hotspot a tablet.
--   * Optional tablespaces ts_us_east_1 / ts_us_west_2 / ts_eu_west_1 attach
--     partitions to regional placement. Create them before this script on a
--     multi-region universe; they are skipped when absent (single-region / local).
--
-- Write-path principle:
--   Consecutive statements that must commit together are written as data-modifying
--   CTEs (WITH ... INSERT/UPDATE ... RETURNING) so YSQL issues one statement
--   rather than N client or PL/pgSQL round trips.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE SCHEMA IF NOT EXISTS rtp;
SET search_path = rtp, public;

-- ============================================================================
-- Optional regional tablespaces (no-op create; attach happens in partition helper)
-- Placement JSON is a template — adjust cloud/region/zone to the universe.
-- ============================================================================
-- CREATE TABLESPACE ts_us_east_1 WITH (replica_placement = $$
--   {"num_replicas": 3, "placement_blocks": [
--     {"cloud": "aws", "region": "us-east-1", "zone": "us-east-1a", "min_num_replicas": 1},
--     {"cloud": "aws", "region": "us-east-1", "zone": "us-east-1b", "min_num_replicas": 1},
--     {"cloud": "aws", "region": "us-east-1", "zone": "us-east-1c", "min_num_replicas": 1}
--   ]}$$);
-- CREATE TABLESPACE ts_us_west_2 WITH (replica_placement = $$
--   {"num_replicas": 3, "placement_blocks": [
--     {"cloud": "aws", "region": "us-west-2", "zone": "us-west-2a", "min_num_replicas": 1},
--     {"cloud": "aws", "region": "us-west-2", "zone": "us-west-2b", "min_num_replicas": 1},
--     {"cloud": "aws", "region": "us-west-2", "zone": "us-west-2c", "min_num_replicas": 1}
--   ]}$$);
-- CREATE TABLESPACE ts_eu_west_1 WITH (replica_placement = $$
--   {"num_replicas": 3, "placement_blocks": [
--     {"cloud": "aws", "region": "eu-west-1", "zone": "eu-west-1a", "min_num_replicas": 1},
--     {"cloud": "aws", "region": "eu-west-1", "zone": "eu-west-1b", "min_num_replicas": 1},
--     {"cloud": "aws", "region": "eu-west-1", "zone": "eu-west-1c", "min_num_replicas": 1}
--   ]}$$);

CREATE OR REPLACE FUNCTION create_geo_partition(
    p_parent text,
    p_region text,
    p_suffix text,
    p_pk_def text,
    p_tablespace text
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    ts_clause text := '';
BEGIN
    IF p_tablespace IS NOT NULL AND EXISTS (
        SELECT 1 FROM pg_tablespace WHERE spcname = p_tablespace
    ) THEN
        ts_clause := ' TABLESPACE ' || quote_ident(p_tablespace);
    END IF;

    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I (%s) FOR VALUES IN (%L)%s',
        p_parent || '_' || p_suffix,
        p_parent,
        p_pk_def,
        p_region,
        ts_clause
    );
END;
$$;

CREATE OR REPLACE FUNCTION create_geo_default_partition(
    p_parent text,
    p_pk_def text
)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I (%s) DEFAULT',
        p_parent || '_default',
        p_parent,
        p_pk_def
    );
END;
$$;

-- ============================================================================
-- Types
-- ============================================================================

CREATE TYPE party_type AS ENUM (
    'PERSON',
    'BUSINESS',
    'FINANCIAL_INSTITUTION',
    'GOVERNMENT'
);

CREATE TYPE account_status AS ENUM (
    'PENDING',
    'OPEN',
    'RESTRICTED',
    'FROZEN',
    'CLOSED'
);

CREATE TYPE account_type AS ENUM (
    'DDA',
    'SAVINGS',
    'PREPAID',
    'LOAN',
    'INTERNAL_CLEARING',
    'NOSTRO',
    'VOSTRO',
    'SUSPENSE',
    'FEE_REVENUE',
    'LOSS_RESERVE'
);

CREATE TYPE payment_direction AS ENUM ('OUTBOUND', 'INBOUND');

CREATE TYPE payment_rail AS ENUM (
    'RTP',
    'FEDNOW',
    'INTERNAL'
);

CREATE TYPE payment_status AS ENUM (
    'RECEIVED',
    'VALIDATING',
    'PENDING_REVIEW',
    'APPROVED',
    'REJECTED',
    'QUEUED',
    'SUBMITTED',
    'ACCEPTED',
    'SETTLED',
    'RETURN_REQUESTED',
    'RETURNED',
    'FAILED',
    'CANCELLED',
    'EXPIRED'
);

CREATE TYPE event_type AS ENUM (
    'RECEIVED',
    'VALIDATION_STARTED',
    'VALIDATION_PASSED',
    'VALIDATION_FAILED',
    'SANCTIONS_SCREENING_STARTED',
    'SANCTIONS_SCREENING_PASSED',
    'SANCTIONS_SCREENING_HIT',
    'FRAUD_SCREENING_STARTED',
    'FRAUD_SCREENING_PASSED',
    'FRAUD_SCREENING_REVIEW',
    'APPROVED',
    'REJECTED',
    'RESERVATION_CREATED',
    'SUBMITTED_TO_NETWORK',
    'NETWORK_ACCEPTED',
    'NETWORK_REJECTED',
    'SETTLED',
    'RETURN_REQUESTED',
    'RETURNED',
    'POSTING_FAILED',
    'CANCELLED',
    'EXPIRED'
);

CREATE TYPE review_type AS ENUM ('AML', 'SANCTIONS', 'FRAUD', 'OPERATIONS');
CREATE TYPE review_status AS ENUM ('OPEN', 'IN_PROGRESS', 'CLEARED', 'ESCALATED', 'DECLINED');

CREATE TYPE ledger_entry_type AS ENUM (
    'PAYMENT_SETTLEMENT',
    'PAYMENT_RETURN',
    'FEE',
    'REVERSAL',
    'ADJUSTMENT',
    'RESERVATION',
    'RESERVATION_RELEASE'
);

CREATE TYPE debit_credit AS ENUM ('DEBIT', 'CREDIT');

-- ============================================================================
-- Globally replicated reference (small). Prefer a tablespace with one replica
-- per region so lookups are local; HASH PK avoids range hotspots.
-- ============================================================================

CREATE TABLE legal_entities (
    legal_entity_id       uuid NOT NULL DEFAULT gen_random_uuid(),
    legal_name            text NOT NULL,
    lei                   varchar(20),
    routing_number        varchar(9),
    bic                   varchar(11),
    created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (legal_entity_id HASH),
    UNIQUE (lei),
    UNIQUE (routing_number),
    UNIQUE (bic)
);

-- ============================================================================
-- Parties, accounts, and payment addresses (geo-partitioned)
-- ============================================================================

CREATE TABLE parties (
    party_id              uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    party_type            party_type NOT NULL,
    legal_entity_id       uuid REFERENCES legal_entities(legal_entity_id),
    display_name          text NOT NULL,
    tax_id_token          text,
    customer_reference    text,
    email                 text,
    phone_e164            varchar(20),
    is_active             boolean NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (
        (party_type = 'FINANCIAL_INSTITUTION' AND legal_entity_id IS NOT NULL)
        OR party_type <> 'FINANCIAL_INSTITUTION'
    ),
    PRIMARY KEY (party_id, geo_partition)
) PARTITION BY LIST (geo_partition);

CREATE TABLE accounts (
    account_id            uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    owning_party_id       uuid NOT NULL,
    ledger_entity_id      uuid NOT NULL REFERENCES legal_entities(legal_entity_id),
    account_type          account_type NOT NULL,
    currency_code         char(3) NOT NULL DEFAULT 'USD',
    account_number_token  text NOT NULL,
    account_suffix        varchar(4),
    status                account_status NOT NULL DEFAULT 'PENDING',
    available_balance     numeric(20,2) NOT NULL DEFAULT 0,
    current_balance       numeric(20,2) NOT NULL DEFAULT 0,
    overdraft_limit       numeric(20,2) NOT NULL DEFAULT 0,
    opened_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    closed_at             timestamptz,
    version               bigint NOT NULL DEFAULT 0,
    CHECK (currency_code ~ '^[A-Z]{3}$'),
    CHECK (overdraft_limit >= 0),
    CHECK (closed_at IS NULL OR closed_at >= opened_at),
    PRIMARY KEY (account_id, geo_partition),
    UNIQUE (ledger_entity_id, account_number_token, geo_partition),
    FOREIGN KEY (owning_party_id, geo_partition)
        REFERENCES parties (party_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

CREATE TABLE payment_addresses (
    payment_address_id    uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    party_id              uuid NOT NULL,
    account_id            uuid,
    rail                  payment_rail NOT NULL,
    address_type          varchar(30) NOT NULL,
    routing_token         text,
    account_token         text,
    alias_value           text,
    is_verified           boolean NOT NULL DEFAULT false,
    is_active             boolean NOT NULL DEFAULT true,
    valid_from            timestamptz NOT NULL DEFAULT clock_timestamp(),
    valid_to              timestamptz,
    created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    CHECK (
        account_id IS NOT NULL
        OR (routing_token IS NOT NULL AND account_token IS NOT NULL)
        OR alias_value IS NOT NULL
    ),
    PRIMARY KEY (payment_address_id, geo_partition),
    FOREIGN KEY (party_id, geo_partition)
        REFERENCES parties (party_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (account_id, geo_partition)
        REFERENCES accounts (account_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

-- ============================================================================
-- Inbound API idempotency and payment instruction
-- ============================================================================

-- No reverse FK to payments (avoids DEFERRABLE circular FKs, which add commit
-- chatter in YugabyteDB). payments.idempotency_key_id references this table.
CREATE TABLE idempotency_keys (
    idempotency_key_id    uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    client_id             text NOT NULL,
    idempotency_key       text NOT NULL,
    request_hash          bytea NOT NULL,
    payment_id            uuid,
    response_code         integer,
    response_body         jsonb,
    first_seen_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at            timestamptz NOT NULL,
    CHECK (expires_at > first_seen_at),
    PRIMARY KEY (idempotency_key_id, geo_partition),
    UNIQUE (client_id, idempotency_key, geo_partition)
) PARTITION BY LIST (geo_partition);

CREATE TABLE payments (
    payment_id                    uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition                 text NOT NULL DEFAULT yb_server_region(),
    network_payment_id            varchar(64),
    end_to_end_id                 varchar(64) NOT NULL,
    instruction_id                varchar(64) NOT NULL,
    client_id                     text,
    idempotency_key_id            uuid,

    rail                          payment_rail NOT NULL,
    direction                     payment_direction NOT NULL,
    status                        payment_status NOT NULL DEFAULT 'RECEIVED',
    status_reason_code            varchar(35),
    status_reason_detail          text,

    debtor_party_id               uuid NOT NULL,
    debtor_account_id             uuid,
    debtor_address_id             uuid,
    creditor_party_id             uuid NOT NULL,
    creditor_account_id           uuid,
    creditor_address_id           uuid,

    amount                        numeric(20,2) NOT NULL,
    currency_code                 char(3) NOT NULL DEFAULT 'USD',
    requested_execution_at        timestamptz,
    received_at                   timestamptz NOT NULL DEFAULT clock_timestamp(),
    accepted_at                   timestamptz,
    settled_at                    timestamptz,
    expires_at                    timestamptz,

    remittance_information        text,
    purpose_code                  varchar(35),
    structured_remittance         jsonb NOT NULL DEFAULT '{}'::jsonb,
    external_metadata             jsonb NOT NULL DEFAULT '{}'::jsonb,
    version                       bigint NOT NULL DEFAULT 0,
    last_event_sequence           integer NOT NULL DEFAULT 0,

    CHECK (amount > 0),
    CHECK (currency_code ~ '^[A-Z]{3}$'),
    CHECK (settled_at IS NULL OR settled_at >= received_at),
    CHECK (accepted_at IS NULL OR accepted_at >= received_at),
    CHECK (expires_at IS NULL OR expires_at > received_at),
    PRIMARY KEY (payment_id, geo_partition),
    UNIQUE (rail, end_to_end_id, geo_partition),
    UNIQUE (rail, instruction_id, geo_partition),
    UNIQUE (idempotency_key_id, geo_partition),
    FOREIGN KEY (idempotency_key_id, geo_partition)
        REFERENCES idempotency_keys (idempotency_key_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (debtor_party_id, geo_partition)
        REFERENCES parties (party_id, geo_partition),
    FOREIGN KEY (creditor_party_id, geo_partition)
        REFERENCES parties (party_id, geo_partition),
    FOREIGN KEY (debtor_account_id, geo_partition)
        REFERENCES accounts (account_id, geo_partition),
    FOREIGN KEY (creditor_account_id, geo_partition)
        REFERENCES accounts (account_id, geo_partition),
    FOREIGN KEY (debtor_address_id, geo_partition)
        REFERENCES payment_addresses (payment_address_id, geo_partition),
    FOREIGN KEY (creditor_address_id, geo_partition)
        REFERENCES payment_addresses (payment_address_id, geo_partition)
) PARTITION BY LIST (geo_partition);

-- Append-only event history. Clustered by payment_id HASH so a payment's
-- lifecycle is one tablet lookup; event_sequence is the range key.
CREATE TABLE payment_events (
    payment_event_id      uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    payment_id            uuid NOT NULL,
    event_sequence        integer NOT NULL,
    event_type            event_type NOT NULL,
    prior_status          payment_status,
    resulting_status      payment_status,
    actor_type            varchar(30) NOT NULL,
    actor_id              text,
    occurred_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    correlation_id        uuid NOT NULL DEFAULT gen_random_uuid(),
    causation_id          uuid,
    detail                jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (payment_id, event_sequence, geo_partition),
    FOREIGN KEY (payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

-- Outbox: PK hashes on aggregate_id (the payment_id) so the outbox
-- row lands in the same hash space as the payment. Publishers claim with
-- claim_outbox_events(), which is a single UPDATE/RETURNING CTE, then DELETE
-- after successful publish to avoid LSM tombstone buildup from updates.
CREATE TABLE outbox_events (
    outbox_event_id       uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    aggregate_type        varchar(50) NOT NULL,
    aggregate_id          uuid NOT NULL,
    event_type            varchar(100) NOT NULL,
    payload               jsonb NOT NULL,
    headers               jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at          timestamptz,
    publish_attempts      integer NOT NULL DEFAULT 0,
    last_error            text,
    PRIMARY KEY (aggregate_id, outbox_event_id, geo_partition)
) PARTITION BY LIST (geo_partition);

-- ============================================================================
-- Compliance and operational workflow
-- ============================================================================

CREATE TABLE screening_results (
    screening_result_id   uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    payment_id            uuid NOT NULL,
    screening_type        review_type NOT NULL,
    provider              text NOT NULL,
    provider_reference    text,
    decision              varchar(20) NOT NULL,
    risk_score            numeric(7,4),
    rules_version         text,
    screened_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    raw_result            jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK (decision IN ('PASS', 'REVIEW', 'FAIL')),
    CHECK (risk_score IS NULL OR (risk_score >= 0 AND risk_score <= 1)),
    PRIMARY KEY (payment_id, screening_result_id, geo_partition),
    FOREIGN KEY (payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

CREATE TABLE payment_reviews (
    review_id             uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    payment_id            uuid NOT NULL,
    review_type           review_type NOT NULL,
    status                review_status NOT NULL DEFAULT 'OPEN',
    priority              smallint NOT NULL DEFAULT 50,
    assigned_to           text,
    opened_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    resolved_at           timestamptz,
    resolution_code       varchar(35),
    resolution_notes      text,
    CHECK (priority BETWEEN 1 AND 100),
    CHECK ((status IN ('CLEARED', 'ESCALATED', 'DECLINED')) = (resolved_at IS NOT NULL)),
    PRIMARY KEY (review_id, geo_partition),
    FOREIGN KEY (payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

-- ============================================================================
-- Double-entry ledger and account holds
-- ============================================================================

CREATE TABLE ledger_transactions (
    ledger_transaction_id uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    payment_id            uuid,
    entry_type            ledger_entry_type NOT NULL,
    effective_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    posted_at             timestamptz NOT NULL DEFAULT clock_timestamp(),
    external_reference    text,
    description           text NOT NULL,
    correlation_id        uuid NOT NULL DEFAULT gen_random_uuid(),
    reversal_of_id        uuid,
    created_by            text NOT NULL,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (ledger_transaction_id, geo_partition),
    FOREIGN KEY (payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

CREATE TABLE ledger_postings (
    ledger_posting_id     uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    ledger_transaction_id uuid NOT NULL,
    account_id            uuid NOT NULL,
    direction             debit_credit NOT NULL,
    amount                numeric(20,2) NOT NULL,
    currency_code         char(3) NOT NULL DEFAULT 'USD',
    created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (amount > 0),
    CHECK (currency_code ~ '^[A-Z]{3}$'),
    PRIMARY KEY (ledger_transaction_id, ledger_posting_id, geo_partition),
    FOREIGN KEY (ledger_transaction_id, geo_partition)
        REFERENCES ledger_transactions (ledger_transaction_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (account_id, geo_partition)
        REFERENCES accounts (account_id, geo_partition)
) PARTITION BY LIST (geo_partition);

CREATE TABLE account_reservations (
    reservation_id        uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    account_id            uuid NOT NULL,
    payment_id            uuid NOT NULL,
    amount                numeric(20,2) NOT NULL,
    currency_code         char(3) NOT NULL DEFAULT 'USD',
    status                varchar(20) NOT NULL DEFAULT 'ACTIVE',
    created_at            timestamptz NOT NULL DEFAULT clock_timestamp(),
    released_at           timestamptz,
    expires_at            timestamptz NOT NULL,
    CHECK (amount > 0),
    CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CHECK (expires_at > created_at),
    PRIMARY KEY (reservation_id, geo_partition),
    UNIQUE (payment_id, geo_partition),
    FOREIGN KEY (account_id, geo_partition)
        REFERENCES accounts (account_id, geo_partition),
    FOREIGN KEY (payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

-- ============================================================================
-- Return requests and immutable audit trail
-- ============================================================================

CREATE TABLE payment_returns (
    return_id             uuid NOT NULL DEFAULT gen_random_uuid(),
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    original_payment_id   uuid NOT NULL,
    return_payment_id     uuid,
    return_reason_code    varchar(35) NOT NULL,
    requested_by          text NOT NULL,
    requested_at          timestamptz NOT NULL DEFAULT clock_timestamp(),
    accepted_at           timestamptz,
    settled_at            timestamptz,
    notes                 text,
    CHECK (return_payment_id IS NULL OR return_payment_id <> original_payment_id),
    PRIMARY KEY (return_id, geo_partition),
    UNIQUE (return_payment_id, geo_partition),
    FOREIGN KEY (original_payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (return_payment_id, geo_partition)
        REFERENCES payments (payment_id, geo_partition)
        DEFERRABLE INITIALLY DEFERRED
) PARTITION BY LIST (geo_partition);

-- IDENTITY + HASH: sequential ids without a range-tablet append hotspot.
CREATE TABLE audit_log (
    audit_log_id          bigint GENERATED ALWAYS AS IDENTITY,
    geo_partition         text NOT NULL DEFAULT yb_server_region(),
    occurred_at           timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor_type            varchar(30) NOT NULL,
    actor_id              text,
    action                varchar(100) NOT NULL,
    resource_type         varchar(100) NOT NULL,
    resource_id           text NOT NULL,
    correlation_id        uuid,
    source_ip             inet,
    user_agent            text,
    before_state          jsonb,
    after_state           jsonb,
    metadata              jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (audit_log_id, geo_partition)
) PARTITION BY LIST (geo_partition);

-- ============================================================================
-- Regional partitions (HASH on the id, geo_partition as list key)
-- ============================================================================

DO $$
DECLARE
    r text;
    ts text;
    sfx text;
BEGIN
    FOREACH r IN ARRAY ARRAY['us-east-1', 'us-west-2', 'eu-west-1'] LOOP
        ts := CASE r
            WHEN 'us-east-1' THEN 'ts_us_east_1'
            WHEN 'us-west-2' THEN 'ts_us_west_2'
            WHEN 'eu-west-1' THEN 'ts_eu_west_1'
        END;
        sfx := replace(r, '-', '_');

        PERFORM create_geo_partition('parties', r, sfx,
            'PRIMARY KEY (party_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('accounts', r, sfx,
            'PRIMARY KEY (account_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('payment_addresses', r, sfx,
            'PRIMARY KEY (payment_address_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('idempotency_keys', r, sfx,
            'PRIMARY KEY (idempotency_key_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('payments', r, sfx,
            'PRIMARY KEY (payment_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('payment_events', r, sfx,
            'PRIMARY KEY (payment_id HASH, event_sequence ASC, geo_partition)', ts);
        PERFORM create_geo_partition('outbox_events', r, sfx,
            'PRIMARY KEY (aggregate_id HASH, outbox_event_id ASC, geo_partition)', ts);
        PERFORM create_geo_partition('screening_results', r, sfx,
            'PRIMARY KEY (payment_id HASH, screening_result_id ASC, geo_partition)', ts);
        PERFORM create_geo_partition('payment_reviews', r, sfx,
            'PRIMARY KEY (review_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('ledger_transactions', r, sfx,
            'PRIMARY KEY (ledger_transaction_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('ledger_postings', r, sfx,
            'PRIMARY KEY (ledger_transaction_id HASH, ledger_posting_id ASC, geo_partition)', ts);
        PERFORM create_geo_partition('account_reservations', r, sfx,
            'PRIMARY KEY (reservation_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('payment_returns', r, sfx,
            'PRIMARY KEY (return_id HASH, geo_partition)', ts);
        PERFORM create_geo_partition('audit_log', r, sfx,
            'PRIMARY KEY (audit_log_id HASH, geo_partition)', ts);
    END LOOP;

    PERFORM create_geo_default_partition('parties',
        'PRIMARY KEY (party_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('accounts',
        'PRIMARY KEY (account_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('payment_addresses',
        'PRIMARY KEY (payment_address_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('idempotency_keys',
        'PRIMARY KEY (idempotency_key_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('payments',
        'PRIMARY KEY (payment_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('payment_events',
        'PRIMARY KEY (payment_id HASH, event_sequence ASC, geo_partition)');
    PERFORM create_geo_default_partition('outbox_events',
        'PRIMARY KEY (aggregate_id HASH, outbox_event_id ASC, geo_partition)');
    PERFORM create_geo_default_partition('screening_results',
        'PRIMARY KEY (payment_id HASH, screening_result_id ASC, geo_partition)');
    PERFORM create_geo_default_partition('payment_reviews',
        'PRIMARY KEY (review_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('ledger_transactions',
        'PRIMARY KEY (ledger_transaction_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('ledger_postings',
        'PRIMARY KEY (ledger_transaction_id HASH, ledger_posting_id ASC, geo_partition)');
    PERFORM create_geo_default_partition('account_reservations',
        'PRIMARY KEY (reservation_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('payment_returns',
        'PRIMARY KEY (return_id HASH, geo_partition)');
    PERFORM create_geo_default_partition('audit_log',
        'PRIMARY KEY (audit_log_id HASH, geo_partition)');
END;
$$;

-- Self-FK on ledger reversals (same region).
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ledger_transactions_reversal_fk
    FOREIGN KEY (reversal_of_id, geo_partition)
    REFERENCES ledger_transactions (ledger_transaction_id, geo_partition)
    DEFERRABLE INITIALLY DEFERRED;

-- Network payment id is unique only when present (no NULLS NOT DISTINCT).
CREATE UNIQUE INDEX payments_network_id_uq
    ON payments (rail, network_payment_id, geo_partition)
    WHERE network_payment_id IS NOT NULL;

CREATE UNIQUE INDEX payment_addresses_active_alias_uq
    ON payment_addresses (rail, address_type, alias_value, geo_partition)
    WHERE is_active AND alias_value IS NOT NULL;

CREATE INDEX parties_customer_reference_idx
    ON parties (customer_reference HASH)
    WHERE customer_reference IS NOT NULL;

CREATE INDEX payment_addresses_party_idx
    ON payment_addresses (party_id HASH)
    WHERE is_active;

CREATE INDEX accounts_owner_idx
    ON accounts (owning_party_id HASH, status);

CREATE INDEX idempotency_keys_expiry_idx
    ON idempotency_keys ((mod(yb_hash_code(idempotency_key_id), 8)) ASC, expires_at ASC);

-- Processing queue: bucket prefix so monotonic received_at does not hotspot.
CREATE INDEX payments_processing_idx
    ON payments ((mod(yb_hash_code(payment_id), 8)) ASC, received_at ASC)
    WHERE status IN ('RECEIVED', 'VALIDATING', 'APPROVED', 'QUEUED', 'SUBMITTED');

CREATE INDEX payments_debtor_idx
    ON payments (debtor_party_id HASH, received_at DESC)
    INCLUDE (status, amount, currency_code);

CREATE INDEX payments_creditor_idx
    ON payments (creditor_party_id HASH, received_at DESC)
    INCLUDE (status, amount, currency_code);

CREATE INDEX screening_results_payment_idx
    ON screening_results (payment_id HASH, screening_type, screened_at DESC);

CREATE INDEX payment_reviews_open_idx
    ON payment_reviews (priority DESC, opened_at)
    WHERE status IN ('OPEN', 'IN_PROGRESS');

CREATE INDEX ledger_transactions_payment_idx
    ON ledger_transactions (payment_id HASH, effective_at);

CREATE INDEX ledger_postings_account_idx
    ON ledger_postings (account_id HASH, created_at DESC);

CREATE INDEX account_reservations_active_idx
    ON account_reservations (account_id HASH, expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX payment_returns_original_idx
    ON payment_returns (original_payment_id HASH, requested_at DESC);

CREATE INDEX audit_log_resource_idx
    ON audit_log (resource_type, resource_id, occurred_at DESC);

CREATE INDEX audit_log_occurred_at_idx
    ON audit_log ((mod(yb_hash_code(audit_log_id), 8)) ASC, occurred_at DESC);

-- Unpublished outbox: bucket + occurred_at so pollers scale without a single
-- hot range tablet. Filter to the local geo_partition in the claim CTE.
CREATE INDEX outbox_events_unpublished_idx
    ON outbox_events ((mod(yb_hash_code(outbox_event_id), 8)) ASC, occurred_at ASC)
    WHERE published_at IS NULL;

-- Unique settlement/return per payment+reference within a region.
CREATE UNIQUE INDEX ledger_transactions_idempotent_uq
    ON ledger_transactions (payment_id, entry_type, geo_partition, external_reference)
    WHERE payment_id IS NOT NULL AND external_reference IS NOT NULL;

-- ============================================================================
-- Ledger balance: statement-level trigger (postings for a txn inserted together)
-- ============================================================================

CREATE OR REPLACE FUNCTION assert_ledger_transaction_balanced()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM (
              SELECT lp.ledger_transaction_id, lp.currency_code,
                     SUM(CASE WHEN lp.direction = 'DEBIT' THEN lp.amount ELSE -lp.amount END) AS net_amount
                FROM ledger_postings lp
               WHERE (lp.ledger_transaction_id, lp.geo_partition) IN (
                     SELECT DISTINCT ledger_transaction_id, geo_partition FROM new_postings
               )
               GROUP BY lp.ledger_transaction_id, lp.currency_code
          ) balances
         WHERE net_amount <> 0
    ) THEN
        RAISE EXCEPTION 'Ledger transaction is not balanced';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER ledger_postings_must_balance
AFTER INSERT ON ledger_postings
REFERENCING NEW TABLE AS new_postings
FOR EACH STATEMENT
EXECUTE FUNCTION assert_ledger_transaction_balanced();

-- ============================================================================
-- CTE write paths
-- ============================================================================

-- Lock payment, bump status + event sequence, append event, append outbox.
CREATE OR REPLACE FUNCTION transition_payment(
    p_payment_id uuid,
    p_new_status payment_status,
    p_event_type event_type,
    p_actor_type varchar,
    p_actor_id text DEFAULT NULL,
    p_reason_code varchar DEFAULT NULL,
    p_reason_detail text DEFAULT NULL,
    p_detail jsonb DEFAULT '{}'::jsonb,
    p_geo_partition text DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
AS $$
DECLARE
    v_geo text := COALESCE(p_geo_partition, yb_server_region());
    v_payment_id uuid;
    v_old_status payment_status;
BEGIN
    WITH locked AS (
        SELECT payment_id, geo_partition, status AS old_status, last_event_sequence
          FROM payments
         WHERE payment_id = p_payment_id
           AND geo_partition = v_geo
         FOR UPDATE
    ),
    guarded AS (
        SELECT *
          FROM locked
         WHERE NOT (
             old_status IN ('SETTLED', 'RETURNED', 'CANCELLED', 'EXPIRED')
             AND p_new_status IS DISTINCT FROM old_status
         )
    ),
    upd AS (
        UPDATE payments p
           SET status = p_new_status,
               status_reason_code = p_reason_code,
               status_reason_detail = p_reason_detail,
               accepted_at = CASE WHEN p_new_status = 'ACCEPTED' THEN clock_timestamp() ELSE p.accepted_at END,
               settled_at = CASE WHEN p_new_status = 'SETTLED' THEN clock_timestamp() ELSE p.settled_at END,
               version = p.version + 1,
               last_event_sequence = g.last_event_sequence + 1
          FROM guarded g
         WHERE p.payment_id = g.payment_id
           AND p.geo_partition = g.geo_partition
        RETURNING p.payment_id, p.geo_partition, g.old_status, p.status, p.last_event_sequence
    ),
    ev AS (
        INSERT INTO payment_events (
            geo_partition, payment_id, event_sequence, event_type, prior_status,
            resulting_status, actor_type, actor_id, detail
        )
        SELECT u.geo_partition, u.payment_id, u.last_event_sequence, p_event_type,
               u.old_status, u.status, p_actor_type, p_actor_id, p_detail
          FROM upd u
        RETURNING payment_id
    ),
    ob AS (
        INSERT INTO outbox_events (
            geo_partition, aggregate_type, aggregate_id, event_type, payload
        )
        SELECT u.geo_partition,
               'payment',
               u.payment_id,
               'payment.' || lower(u.status::text),
               jsonb_build_object(
                   'payment_id', u.payment_id,
                   'geo_partition', u.geo_partition,
                   'prior_status', u.old_status,
                   'status', u.status,
                   'reason_code', p_reason_code,
                   'occurred_at', clock_timestamp()
               )
          FROM upd u
        RETURNING aggregate_id
    )
    SELECT u.payment_id, u.old_status
      INTO v_payment_id, v_old_status
      FROM upd u;

    IF v_payment_id IS NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM payments WHERE payment_id = p_payment_id AND geo_partition = v_geo
        ) THEN
            RAISE EXCEPTION 'Payment % does not exist in geo_partition %', p_payment_id, v_geo;
        END IF;
        RAISE EXCEPTION 'Cannot transition terminal payment % from % to %',
            p_payment_id, v_old_status, p_new_status;
    END IF;

    RETURN v_payment_id;
END;
$$;

-- Settlement: lock payment, post balanced ledger pair, consume hold, transition.
CREATE OR REPLACE FUNCTION settle_outbound_payment(
    p_payment_id uuid,
    p_settlement_account_id uuid,
    p_actor_id text,
    p_geo_partition text DEFAULT NULL
)
RETURNS uuid
LANGUAGE plpgsql
AS $$
DECLARE
    v_geo text := COALESCE(p_geo_partition, yb_server_region());
    v_ledger_transaction_id uuid;
BEGIN
    WITH pmt AS (
        SELECT *
          FROM payments
         WHERE payment_id = p_payment_id
           AND geo_partition = v_geo
           AND direction = 'OUTBOUND'
         FOR UPDATE
    ),
    already AS (
        SELECT lt.ledger_transaction_id
          FROM ledger_transactions lt
          JOIN pmt ON lt.payment_id = pmt.payment_id
                  AND lt.geo_partition = pmt.geo_partition
         WHERE lt.entry_type = 'PAYMENT_SETTLEMENT'
           AND pmt.status = 'SETTLED'
         LIMIT 1
    ),
    eligible AS (
        SELECT pmt.*
          FROM pmt
         WHERE pmt.status IN ('ACCEPTED', 'SUBMITTED')
           AND NOT EXISTS (SELECT 1 FROM already)
    ),
    lt AS (
        INSERT INTO ledger_transactions (
            geo_partition, payment_id, entry_type, external_reference,
            description, created_by, metadata
        )
        SELECT e.geo_partition,
               e.payment_id,
               'PAYMENT_SETTLEMENT',
               e.network_payment_id,
               'Outbound real-time payment settlement',
               p_actor_id,
               jsonb_build_object('rail', e.rail, 'end_to_end_id', e.end_to_end_id)
          FROM eligible e
        RETURNING ledger_transaction_id, geo_partition, payment_id
    ),
    lp AS (
        INSERT INTO ledger_postings (
            geo_partition, ledger_transaction_id, account_id, direction, amount, currency_code
        )
        SELECT lt.geo_partition, lt.ledger_transaction_id, e.debtor_account_id,
               'DEBIT'::debit_credit, e.amount, e.currency_code
          FROM lt
          JOIN eligible e ON e.payment_id = lt.payment_id AND e.geo_partition = lt.geo_partition
        UNION ALL
        SELECT lt.geo_partition, lt.ledger_transaction_id, p_settlement_account_id,
               'CREDIT'::debit_credit, e.amount, e.currency_code
          FROM lt
          JOIN eligible e ON e.payment_id = lt.payment_id AND e.geo_partition = lt.geo_partition
        RETURNING ledger_transaction_id
    ),
    rsv AS (
        UPDATE account_reservations ar
           SET status = 'CONSUMED', released_at = clock_timestamp()
          FROM eligible e
         WHERE ar.payment_id = e.payment_id
           AND ar.geo_partition = e.geo_partition
           AND ar.status = 'ACTIVE'
        RETURNING ar.payment_id
    ),
    upd AS (
        UPDATE payments p
           SET status = 'SETTLED',
               settled_at = clock_timestamp(),
               version = p.version + 1,
               last_event_sequence = p.last_event_sequence + 1
          FROM eligible e
         WHERE p.payment_id = e.payment_id
           AND p.geo_partition = e.geo_partition
        RETURNING p.payment_id, p.geo_partition, e.status AS old_status, p.last_event_sequence
    ),
    ev AS (
        INSERT INTO payment_events (
            geo_partition, payment_id, event_sequence, event_type, prior_status,
            resulting_status, actor_type, actor_id, detail
        )
        SELECT u.geo_partition, u.payment_id, u.last_event_sequence, 'SETTLED',
               u.old_status, 'SETTLED', 'SYSTEM', p_actor_id,
               jsonb_build_object('ledger_transaction_id', lt.ledger_transaction_id)
          FROM upd u
          JOIN lt ON lt.payment_id = u.payment_id AND lt.geo_partition = u.geo_partition
        RETURNING payment_id
    ),
    ob AS (
        INSERT INTO outbox_events (
            geo_partition, aggregate_type, aggregate_id, event_type, payload
        )
        SELECT u.geo_partition, 'payment', u.payment_id, 'payment.settled',
               jsonb_build_object(
                   'payment_id', u.payment_id,
                   'geo_partition', u.geo_partition,
                   'prior_status', u.old_status,
                   'status', 'SETTLED',
                   'ledger_transaction_id', lt.ledger_transaction_id,
                   'occurred_at', clock_timestamp()
               )
          FROM upd u
          JOIN lt ON lt.payment_id = u.payment_id AND lt.geo_partition = u.geo_partition
        RETURNING aggregate_id
    )
    SELECT COALESCE(
               (SELECT ledger_transaction_id FROM already),
               (SELECT ledger_transaction_id FROM lt)
           )
      INTO v_ledger_transaction_id;

    IF v_ledger_transaction_id IS NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM payments
             WHERE payment_id = p_payment_id
               AND geo_partition = v_geo
               AND direction = 'OUTBOUND'
        ) THEN
            RAISE EXCEPTION 'Outbound payment % does not exist in geo_partition %',
                p_payment_id, v_geo;
        END IF;
        RAISE EXCEPTION 'Payment % cannot settle from its current status', p_payment_id;
    END IF;

    RETURN v_ledger_transaction_id;
END;
$$;

-- Local-region outbox claim: one CTE, SKIP LOCKED, optional hash bucket so
-- multiple pollers do not contend on the same unpublished range.
CREATE OR REPLACE FUNCTION claim_outbox_events(
    p_limit integer DEFAULT 100,
    p_bucket integer DEFAULT NULL,
    p_bucket_count integer DEFAULT 8,
    p_geo_partition text DEFAULT NULL
)
RETURNS SETOF outbox_events
LANGUAGE sql
AS $$
    WITH picked AS (
        SELECT o.aggregate_id, o.outbox_event_id, o.geo_partition
          FROM outbox_events o
         WHERE o.published_at IS NULL
           AND o.geo_partition = COALESCE(p_geo_partition, yb_server_region())
           AND (
               p_bucket IS NULL
               OR mod(yb_hash_code(o.outbox_event_id), p_bucket_count) = p_bucket
           )
         ORDER BY o.occurred_at
         LIMIT p_limit
         FOR UPDATE SKIP LOCKED
    ),
    claimed AS (
        UPDATE outbox_events o
           SET published_at = clock_timestamp(),
               publish_attempts = o.publish_attempts + 1
          FROM picked p
         WHERE o.aggregate_id = p.aggregate_id
           AND o.outbox_event_id = p.outbox_event_id
           AND o.geo_partition = p.geo_partition
           AND o.published_at IS NULL
        RETURNING o.*
    )
    SELECT * FROM claimed;
$$;

-- After the broker ack, delete the claimed row (preferred over leaving
-- published_at set, which still requires compaction of the updated version).
CREATE OR REPLACE FUNCTION delete_published_outbox_events(
    p_outbox_event_ids uuid[],
    p_geo_partition text DEFAULT NULL
)
RETURNS integer
LANGUAGE sql
AS $$
    WITH deleted AS (
        DELETE FROM outbox_events o
         WHERE o.geo_partition = COALESCE(p_geo_partition, yb_server_region())
           AND o.outbox_event_id = ANY (p_outbox_event_ids)
           AND o.published_at IS NOT NULL
        RETURNING o.outbox_event_id
    )
    SELECT COUNT(*)::integer FROM deleted;
$$;

-- Recommended production controls (apply using institution-specific roles):
--   Use a YugabyteDB smart driver with load_balance=true and topology_keys
--   scoped to the local cloud.region.zone (priority 1) plus regional fallback.
--   SET statement_timeout and idle_in_transaction_session_timeout on connect.
--   Retry 40001 / 40P01 with rollback, jittered backoff, and idempotent writes.
--   Followers: SET yb_read_from_followers = on only inside READ ONLY txns.
--   Stream audit_log and payment_events to immutable WORM-capable retained storage.
