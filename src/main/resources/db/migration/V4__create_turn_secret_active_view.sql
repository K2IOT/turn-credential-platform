CREATE VIEW turn_secret_active AS
SELECT realm, value
FROM turn_secret
UNION
SELECT realm, previous_value AS value
FROM turn_secret
WHERE previous_value IS NOT NULL
  AND previous_valid_until > NOW();
