-- Disposable asset_cache_test ONLY; representative nonempty orders for 100 users.
CREATE TABLE cache_digits(n INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO cache_digits VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9);
CREATE TEMPORARY TABLE cache_numbers AS SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n AS n FROM cache_digits a CROSS JOIN cache_digits b CROSS JOIN cache_digits c CROSS JOIN cache_digits d CROSS JOIN cache_digits e;
INSERT INTO contract_order(user_id,symbol,side,type,status,quantity,profit,fee,lot_size,close_time,created_at,updated_at,row_version,limit_match_enabled)
SELECT MOD(n,100)+1,'FIXTUREUSD','BUY','MARKET',IF(MOD(n,3)=0,'CANCELLED','CLOSED'),1,MOD(n,17)-8,0.25,1,TIMESTAMP('2026-09-28 03:00:00')-INTERVAL MOD(n,365) DAY,NOW(),NOW(),0,0 FROM cache_numbers WHERE n<@fixture_rows;
INSERT INTO option_order(user_id,amount,direction,status,symbol,profit,close_time,created_at,updated_at,row_version)
SELECT MOD(n,100)+1,10,'UP',IF(MOD(n,3)=0,'CANCELLED','CLOSED'),'FIXTUREUSD',MOD(n,17)-8,TIMESTAMP('2026-09-28 03:00:00')-INTERVAL MOD(n,365) DAY,NOW(),NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
INSERT INTO financial_yield_record(user_id,order_id,product_id,product_name,cumulative_yield,daily_yield,status,paid_at,yield_date,created_at,updated_at,row_version)
SELECT MOD(n,100)+1,n+1,1,'fixture',10,0.125,IF(MOD(n,3)=0,'CANCELLED','PAID'),TIMESTAMP('2026-09-28 03:00:00')-INTERVAL MOD(n,365) DAY,'2026-09-28',NOW(),NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
INSERT INTO loan_record(user_id,amount,contract_signed,created_at,daily_rate,days,free_days,repayment_amount,status,total_interest,updated_at,row_version)
SELECT MOD(n,100)+1,10,0,NOW(),0.001,10,1,10,'PENDING',0,NOW(),0 FROM cache_numbers WHERE n<@fixture_rows;
DROP TEMPORARY TABLE cache_numbers;
DROP TABLE cache_digits;
