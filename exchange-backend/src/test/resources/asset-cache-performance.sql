-- DedicatedMysqlFixture-certified, disposable cache clone only. No production schema changes.
-- P01 retains 100000 rows in each of four target tables (400000), plus real parent facts.
-- MySQL 5.7 cannot reopen one TEMPORARY table through five aliases; derive each digit independently.
CREATE TEMPORARY TABLE cache_numbers AS SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n AS n
FROM (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c
CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d
CROSS JOIN (SELECT 0 AS n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) e;
-- Duplicate assertion keys deliberately fail closed, independent of SQL mode.
CREATE TEMPORARY TABLE cache_fixture_assert(failure VARCHAR(64) PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO cache_fixture_assert VALUES('dedicated-maintenance-tenant2'),('100-owned-users'),('target-tables-empty'),('exact-target-counts'),('same-tenant-yield-parents');
START TRANSACTION;
INSERT INTO cache_fixture_assert SELECT 'dedicated-maintenance-tenant2' WHERE LEFT(DATABASE(),12)<>'mt705_probe_' OR @fixture_rows IS NULL OR @fixture_rows<>100000 OR
 (SELECT COUNT(*) FROM tenant WHERE id=2 AND code='stage2_money_a' AND status='MAINTENANCE')<>1 OR
 (SELECT COUNT(*) FROM user_account WHERE tenant_id=2 AND ((id=1 AND email='cache@fixture.invalid') OR (id=2 AND email='cache-other@fixture.invalid')))<>2;
INSERT INTO cache_fixture_assert SELECT 'target-tables-empty' WHERE
 (SELECT COUNT(*) FROM contract_order WHERE tenant_id=2)<>0 OR (SELECT COUNT(*) FROM option_order WHERE tenant_id=2)<>0 OR
 (SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=2)<>0 OR (SELECT COUNT(*) FROM loan_record WHERE tenant_id=2)<>0;
-- Existing IDs belonging to another tenant are never reused or updated; the following count rejects them.
INSERT INTO user_account(tenant_id,id,email,password_hash,status,row_version)
SELECT 2,n+1,CONCAT('cache-perf-',n+1,'@fixture.invalid'),'NOT_A_LOGIN_PASSWORD','normal',0 FROM cache_numbers WHERE n BETWEEN 2 AND 99 AND NOT EXISTS(SELECT 1 FROM user_account u WHERE u.id=n+1);
INSERT INTO cache_fixture_assert SELECT '100-owned-users' WHERE (SELECT COUNT(*) FROM user_account WHERE tenant_id=2 AND id BETWEEN 1 AND 100 AND (id IN(1,2) OR email=CONCAT('cache-perf-',id,'@fixture.invalid')))<>100;
INSERT INTO financial_product(tenant_id,name,currency,daily_yield_rate,rental_fee,min_purchase,max_purchase,term_days,enabled,created_at,updated_at)
VALUES(2,'cache-performance-fixture','USD',1.25,0,10,10,80,0,NOW(),NOW());
SET @cache_product_id=LAST_INSERT_ID();
-- Exclusive disposable clone; allocate IDs above every existing order without changing historical rows.
SET @cache_order_base=(SELECT COALESCE(MAX(id),0) FROM financial_order);
INSERT INTO financial_order(tenant_id,id,user_id,product_id,product_name,purchase_amount,currency,daily_yield_rate,daily_yield,total_yield,term_days,status,purchase_time,end_time,created_at,updated_at,row_version)
SELECT 2,@cache_order_base+n+1,MOD(n,100)+1,@cache_product_id,'cache-performance-fixture',10,'USD',1.25,0.125,10,80,'COMPLETED',TIMESTAMP('2026-07-10 03:00:00'),TIMESTAMP('2026-09-28 03:00:00'),NOW(),NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
INSERT INTO contract_order(tenant_id,user_id,symbol,side,type,status,quantity,profit,fee,lot_size,close_time,created_at,updated_at,row_version,limit_match_enabled)
SELECT 2,MOD(n,100)+1,'FIXTUREUSD','BUY','MARKET',IF(MOD(n,3)=0,'CANCELLED','CLOSED'),1,MOD(n,17)-8,0.25,1,TIMESTAMP('2026-09-28 03:00:00')-INTERVAL MOD(n,365) DAY,NOW(),NOW(),0,0 FROM cache_numbers WHERE n<@fixture_rows;
INSERT INTO option_order(tenant_id,user_id,amount,direction,status,symbol,profit,close_time,created_at,updated_at,row_version)
SELECT 2,MOD(n,100)+1,10,'UP',IF(MOD(n,3)=0,'CANCELLED','CLOSED'),'FIXTUREUSD',MOD(n,17)-8,TIMESTAMP('2026-09-28 03:00:00')-INTERVAL MOD(n,365) DAY,NOW(),NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
-- Retain the original PAID/negative-status income-filter distribution; CANCELLED is VARCHAR, not an enum.
INSERT INTO financial_yield_record(tenant_id,user_id,order_id,product_id,product_name,cumulative_yield,daily_yield,status,paid_at,yield_date,created_at,updated_at,row_version)
SELECT 2,MOD(n,100)+1,@cache_order_base+n+1,@cache_product_id,'cache-performance-fixture',10,0.125,IF(MOD(n,3)=0,'CANCELLED','PAID'),TIMESTAMP('2026-09-28 03:00:00')-INTERVAL MOD(n,365) DAY,'2026-09-28',NOW(),NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
-- PENDING with no approved_at/actual_repayment_at is not disbursed and creates no loan liability.
INSERT INTO loan_record(tenant_id,user_id,amount,contract_signed,created_at,daily_rate,days,free_days,repayment_amount,status,total_interest,updated_at,row_version)
SELECT 2,MOD(n,100)+1,10,0,NOW(),0.001,10,1,10,'PENDING',0,NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
INSERT INTO cache_fixture_assert SELECT 'exact-target-counts' WHERE
 (SELECT COUNT(*) FROM contract_order WHERE tenant_id=2)<>@fixture_rows OR (SELECT COUNT(*) FROM option_order WHERE tenant_id=2)<>@fixture_rows OR
 (SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=2)<>@fixture_rows OR (SELECT COUNT(*) FROM loan_record WHERE tenant_id=2)<>@fixture_rows;
INSERT INTO cache_fixture_assert SELECT 'same-tenant-yield-parents' WHERE
 (SELECT COUNT(*) FROM financial_yield_record y JOIN financial_order o ON o.tenant_id=y.tenant_id AND o.id=y.order_id AND o.user_id=y.user_id AND o.product_id=y.product_id JOIN financial_product p ON p.tenant_id=y.tenant_id AND p.id=y.product_id WHERE y.tenant_id=2 AND p.id=@cache_product_id AND o.status='COMPLETED')<>@fixture_rows;
COMMIT;
DROP TEMPORARY TABLE cache_numbers;
DROP TEMPORARY TABLE cache_fixture_assert;
