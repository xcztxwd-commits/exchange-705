"""Static fixture safety/scale check. No database, JVM, services or network access."""
import re
from decimal import Decimal
from pathlib import Path

resources = Path(__file__).resolve().parent
backend = resources.parents[2]
sql = (resources / "asset-cache-performance.sql").read_text(encoding="utf-8")
test = (backend / "src/test/java/com/gtcfesk/exchange/user/AssetHistoryCacheIT.java").read_text(encoding="utf-8")
migration = (backend / "src/main/resources/db/migration/V2026092902__multitenant_isolation.sql").read_text(encoding="utf-8")
body = re.sub(r"--[^\n]*", "", sql)

assert 'DedicatedMysqlFixture.fromProperty("cache.mysql.fixture")' in test
assert 'SET @fixture_rows=100000' in test
assert 'for(int i=0;i<100;i++)' in test
assert "code='stage2_money_a' AND status='MAINTENANCE'" in body
assert "password_hash,status,row_version)" in body and "'NOT_A_LOGIN_PASSWORD'" in body
assert "START TRANSACTION;" in body and "COMMIT;" in body
assert not re.search(r"\b(?:ALTER|TRUNCATE|DELETE|UPDATE)\b|FOREIGN_KEY_CHECKS", body, re.I)
assert all("TEMPORARY" in s.upper() for s in re.findall(r"\b(?:CREATE|DROP)\s+[^;]+;", body, re.I))
assert "cache_digits" not in body
digits = re.findall(r"\((SELECT 0 AS n(?: UNION ALL SELECT [1-9]){9})\) ([a-e])", body)
assert [alias for _, alias in digits] == list("abcde")
assert all([int(n) for n in re.findall(r"SELECT ([0-9])", query)] == list(range(10)) for query, _ in digits)
assert "a.n+10*b.n+100*c.n+1000*d.n+10000*e.n AS n" in body

targets = ("contract_order", "option_order", "financial_yield_record", "loan_record")
for table in (*targets, "financial_order", "financial_product", "user_account"):
    assert re.search(rf"INSERT INTO {table}\(tenant_id,", body)
    assert re.search(rf"INSERT INTO {table}\([^;]+\)\s*(?:SELECT|VALUES\()\s*2,", body)
for table in (*targets, "financial_order"):
    assert re.search(rf"INSERT INTO {table}\([^;]+FROM cache_numbers WHERE n<@fixture_rows;", body)
    assert "CALL mt_link('" + table + "','user_id','user_account','id');" in migration
for field, parent in (("order_id", "financial_order"), ("product_id", "financial_product")):
    assert f"CALL mt_link('financial_yield_record','{field}','{parent}','id');" in migration
assert body.index("INSERT INTO financial_product") < body.index("INSERT INTO financial_order") < body.index("INSERT INTO financial_yield_record")
assert "@cache_order_base+n+1,@cache_product_id" in body
assert "o.user_id=y.user_id AND o.product_id=y.product_id" in body
assert "o.status='COMPLETED'" in body
assert body.count("IF(MOD(n,3)=0,'CANCELLED','CLOSED')") == 2
assert "IF(MOD(n,3)=0,'CANCELLED','PAID')" in body
assert "10,'PENDING',0" in body
assert "exact-target-counts" in body and "same-tenant-yield-parents" in body

# Preserve the original five-digit generator, 100 users and bounded settled amounts.
counts = [0] * 100
income_bound = [Decimal(0)] * 100
for n in range(100000):
    user = n % 100
    counts[user] += 1
    if n % 3:
        income_bound[user] += abs(Decimal(2 * (n % 17 - 8)) - Decimal("0.25") + Decimal("0.125"))
assert counts == [1000] * 100
assert max(income_bound) < Decimal("1e16")
print("PASS: static tenant/parent/transaction guards; 100000 x 4 target rows, 100 users; actual MySQL P01 still required")
