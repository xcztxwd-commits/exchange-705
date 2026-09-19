-- Apply after 705-minimal-fix.sql, with writers stopped and a backup retained.
-- Historical orders are deliberately excluded; do not backfill this flag to 1.
ALTER TABLE contract_order ADD COLUMN limit_match_enabled BIT NOT NULL DEFAULT 0;
