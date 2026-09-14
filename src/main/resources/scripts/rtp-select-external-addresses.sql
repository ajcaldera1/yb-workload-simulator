SELECT p.party_id, pa.payment_address_id, pa.rail, pa.geo_partition
  FROM rtp.payment_addresses pa
  JOIN rtp.parties p ON p.party_id = pa.party_id
   AND p.geo_partition = pa.geo_partition
 WHERE p.customer_reference LIKE 'EXT-%' AND pa.is_active
   AND pa.geo_partition = ?
 ORDER BY random() LIMIT ?
