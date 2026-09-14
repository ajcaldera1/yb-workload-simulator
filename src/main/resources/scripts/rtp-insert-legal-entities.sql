WITH ins AS (
    INSERT INTO rtp.legal_entities (legal_name, lei, routing_number, bic)
    VALUES
        (?, ?, ?, ?),
        (?, ?, ?, ?),
        (?, ?, ?, ?)
    RETURNING legal_entity_id, legal_name
)
SELECT legal_entity_id FROM ins WHERE legal_name = ?
