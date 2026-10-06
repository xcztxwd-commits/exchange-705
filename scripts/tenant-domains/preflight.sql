-- READ ONLY. Run only against the exact approved 2026100602 baseline before 0603.
-- All result sets except the epoch row must be empty. Never repair owners by inference.
SELECT MAX(version) AS schema_epoch, MAX(minimum_application_epoch) AS minimum_application_epoch
 FROM tenant_schema_version;
SELECT tenant_id,status,COUNT(*) AS conflicting_slots FROM tenant_domain_binding
 WHERE status='ACTIVE' GROUP BY tenant_id,status HAVING COUNT(*)>1;
SELECT tenant_id,COUNT(*) AS conflicting_candidates FROM tenant_domain_binding
 WHERE status IN ('PENDING','VERIFIED') GROUP BY tenant_id HAVING COUNT(*)>1;
SELECT t.id,t.frontend_host,b.tenant_id AS binding_owner,b.status
 FROM tenant t LEFT JOIN tenant_domain_binding b ON b.hostname=t.frontend_host
 WHERE t.frontend_host IS NOT NULL AND (b.hostname IS NULL OR b.tenant_id<>t.id OR b.status<>'ACTIVE'
   OR (t.domain_verified=1 AND b.verified_at IS NULL));
SELECT t.id,t.frontend_host FROM tenant t
 WHERE t.frontend_host IS NOT NULL AND (t.frontend_host NOT REGEXP '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.forex-exchange\\.cc$'
   OR SUBSTRING_INDEX(t.frontend_host,'.',1) IN ('www','admin','admin-panel','control','control-panel','api','mail','ns1','ns2','status'));
SELECT h.hostname,h.tenant_id AS retired_owner,b.tenant_id AS binding_owner,b.status
 FROM tenant_domain_history h LEFT JOIN tenant_domain_binding b ON b.hostname=h.hostname
 WHERE b.hostname IS NULL OR (b.status='RETIRED' AND b.tenant_id<>h.tenant_id);
