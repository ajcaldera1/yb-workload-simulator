SELECT account_id, owning_party_id, currency_code, geo_partition
  FROM rtp.accounts a
  JOIN rtp.parties p ON p.party_id = a.owning_party_id
   AND p.geo_partition = a.geo_partition
 WHERE p.customer_reference LIKE 'CUST-%' AND a.status = 'OPEN'
   AND a.geo_partition = ?
 ORDER BY random() LIMIT ?
