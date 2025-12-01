-- Fix for duplicate rows in ofPushNotiService table.
-- See https://github.com/igniterealtime/openfire-pushnotification-plugin/issues/50

-- Step 1: Create a temporary table with the same structure as the original
CREATE TABLE ofPushNotiService_dedup (
  username              VARCHAR(64)     NOT NULL,
  service               VARCHAR(1024)   NOT NULL,
  node                  VARCHAR(1024)   NOT NULL,
  options			    TEXT            NULL
);

-- Step 2: Insert only distinct rows into the temporary table
INSERT INTO ofPushNotiService_dedup (username, service, node, options)
SELECT DISTINCT
    username,
    service,
    node,
    options
FROM ofPushNotiService;

-- Step 3: Remove all rows from the original table
DELETE FROM ofPushNotiService;

-- Step 4: Reinsert deduplicated rows back into the original table
INSERT INTO ofPushNotiService (username, service, node, options)
SELECT
    username,
    service,
    node,
    options
FROM ofPushNotiService_dedup;

-- Step 5: Drop the temporary table
DROP TABLE ofPushNotiService_dedup;

UPDATE ofVersion SET version = 2 WHERE name = 'pushnotification'
