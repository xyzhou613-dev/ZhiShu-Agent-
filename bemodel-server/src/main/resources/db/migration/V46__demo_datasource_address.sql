-- Keep historical migration checksums unchanged. Configure the original demo
-- registrations before DataSeeder connects; preserve manually changed addresses.
UPDATE bm_datasource
SET host = '${demo_db_host}', port = ${demo_db_port}
WHERE ds_code IN ('DS_HIS', 'DS_LIS', 'DS_CHARGE', 'DS_PHARMACY',
                  'DS_OPD', 'DS_EMR', 'DS_PACS', 'DS_NURSE', 'DS_MATERIAL')
  AND host = '127.0.0.1' AND port = 3306;
