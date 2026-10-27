SELECT RAWTOHEX(STANDARD_HASH(content, 'SHA256'))
FROM dcm4che_config
WHERE path = ?
