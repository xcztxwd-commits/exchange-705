-- CANDIDATE ONLY. Not an application startup migration; production needs separate approval.
-- Run in an isolated MySQL 5.7 clone first. Record SHOW INDEX before/after.
-- Equivalent BTREE left prefixes (even under other names) are reused.
-- A conflicting same-name index deliberately fails ALTER; inspect instead of replacing it.
SELECT VERSION(), DATABASE();
SET @perf_ddl = IF(EXISTS (
 SELECT 1 FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='market_source_event' AND INDEX_TYPE='BTREE'
 GROUP BY INDEX_NAME
 HAVING SUM(SEQ_IN_INDEX=1 AND COLUMN_NAME='symbol_id' AND SUB_PART IS NULL)=1
    AND SUM(SEQ_IN_INDEX=2 AND COLUMN_NAME='received_at' AND SUB_PART IS NULL)=1
), 'SELECT "SKIP: market_source_event received prefix already exists"',
 'ALTER TABLE market_source_event ADD INDEX idx_source_event_received (symbol_id,received_at), ALGORITHM=INPLACE, LOCK=NONE');
SELECT @perf_ddl;
PREPARE perf_index_statement FROM @perf_ddl;
EXECUTE perf_index_statement;
DEALLOCATE PREPARE perf_index_statement;
SET @perf_ddl = IF(EXISTS (
 SELECT 1 FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='market_source_tick' AND INDEX_TYPE='BTREE'
 GROUP BY INDEX_NAME
 HAVING SUM(SEQ_IN_INDEX=1 AND COLUMN_NAME='symbol_id' AND SUB_PART IS NULL)=1
    AND SUM(SEQ_IN_INDEX=2 AND COLUMN_NAME='received_at' AND SUB_PART IS NULL)=1
), 'SELECT "SKIP: market_source_tick received prefix already exists"',
 'ALTER TABLE market_source_tick ADD INDEX idx_source_tick_received (symbol_id,received_at), ALGORITHM=INPLACE, LOCK=NONE');
SELECT @perf_ddl;
PREPARE perf_index_statement FROM @perf_ddl;
EXECUTE perf_index_statement;
DEALLOCATE PREPARE perf_index_statement;
