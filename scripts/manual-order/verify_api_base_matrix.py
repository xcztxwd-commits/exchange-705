"""Exercise the real manual-order API against six isolated 24-hour base cases.

Use a fresh six-user ID range in the retained manual_browser database. The
minute observations are test fixtures; pricing and order math stay in backend.
"""
import argparse
import json
import uuid
from decimal import Decimal

from curve_fixture import BASIS, ROOT, at, sql, verify_target
from verify_curve_business import OUT, api

CLOSE = at("2026-09-26T17:10:00")
CASES = (
    ("null_positive", 100, -60000, True, "CARRY_24H", "MANUAL_CARRY"),
    ("missing_zero", 0, -60000, False, "CARRY_24H", "MANUAL_CARRY"),
    ("missing_negative", -100, -60000, False, "CARRY_24H", "MANUAL_CARRY"),
    ("exact_24h", 200, -86400000, False, "CARRY_24H", "MANUAL_CARRY"),
    ("older_than_24h", 200, -86460000, False, "ZERO", "MANUAL_ZERO"),
    ("no_base", None, None, False, "ZERO", "MANUAL_ZERO"),
)
TABLES = ("1m", "1h", "4h", "1d")
WIDTHS = {"1h": 3600000, "4h": 14400000, "1d": 86400000}


def state(user):
    return {
        "wallet": sql(f"select cast(available as char),cast(frozen as char),row_version from asset_account where user_id={user} and coin='CONTRACT'"),
        "minutes": sql(f"select bucket_start,coalesce(cast(net_equity as char),'NULL'),coalesce(cast(manual_adjustment as char),'NULL'),origin,effective_at from asset_history_1m where user_id={user} and basis_version='{BASIS}' order by bucket_start"),
        "counts": {grain: int(sql(f"select count(*) from asset_history_{grain} where user_id={user} and basis_version='{BASIS}'")) for grain in TABLES},
        "orders": int(sql(f"select count(*) from contract_order where user_id={user}")),
        "audit": int(sql(f"select count(*) from manual_order_record where user_id={user}")),
    }


def seed(start):
    users = range(start, start + len(CASES))
    assert sql(f"select count(*) from user_account where id between {start} and {start + len(CASES) - 1}") == "0", "Fresh isolated IDs required"
    assert sql(f"select count(*) from contract_order where user_id between {start} and {start + len(CASES) - 1}") == "0"
    statements = ["start transaction;"]
    for user, (name, base, offset, target_null, _, _) in zip(users, CASES):
        email = f"manual-base-{name}-{user}@local.invalid"
        statements.append(
            "insert into user_account(id,email,password_hash,nickname,status,user_type,row_version) "
            f"select {user},'{email}',password_hash,'E2E {name}','normal','normal',0 "
            "from user_account where id=9000003;"
        )
        statements.append(
            "insert into asset_account(user_id,coin,available,frozen,row_version) "
            f"values({user},'CONTRACT',1000,0,0);"
        )
        if offset is not None:
            minute = CLOSE + offset
            statements.append(
                "insert into asset_history_1m(user_id,basis_version,bucket_start,effective_at,net_equity,"
                "valuation_status,reason_code,valuation_evidence,origin,created_at) "
                f"values({user},'{BASIS}',{minute},{minute + 60000},{base},'ESTIMATED','E2E_BASE',"
                "'{}','MANUAL_CARRY',unix_timestamp()*1000);"
            )
        if target_null:
            statements.append(
                "insert into asset_history_1m(user_id,basis_version,bucket_start,effective_at,net_equity,"
                "valuation_status,reason_code,valuation_evidence,origin,created_at) "
                f"values({user},'{BASIS}',{CLOSE},{CLOSE + 60000},NULL,'FAILED','E2E_NULL',"
                "'{}','NORMAL',unix_timestamp()*1000);"
            )
    statements.append("commit;")
    sql("".join(statements))
    assert all(sql(f"select count(*) from user_account where id={user}") == "1" for user in users)


def check_parent(user, grain, minute_rows):
    width = WIDTHS[grain]
    start = CLOSE // width * width
    text = sql(
        f"select open_value,high_value,low_value,close_value,open_at,high_at,low_at,close_at,"
        "source_count,valid_sample_count,invalid_sample_count,finalized "
        f"from asset_history_{grain} where user_id={user} and basis_version='{BASIS}' and bucket_start={start}"
    )
    fields = text.split("\t")
    assert len(fields) == 12, (grain, text)
    values = sorted(
        (int(row[4]), Decimal(row[1])) for row in minute_rows
        if row[1] != "NULL" and start <= int(row[0]) < start + width
    )
    assert values, (grain, minute_rows)
    high = max(value for _, value in values)
    low = min(value for _, value in values)
    expected = (
        values[0][1], high, low, values[-1][1], values[0][0],
        min(time for time, value in values if value == high),
        min(time for time, value in values if value == low), values[-1][0],
        len(values) if grain == "1h" else 1, len(values), 0, 1,
    )
    actual = tuple(Decimal(value) if index < 4 else int(value) for index, value in enumerate(fields))
    assert actual == expected, (grain, actual, expected)
    return {"bucketStart": start, "open": str(actual[0]), "high": str(actual[1]),
            "low": str(actual[2]), "close": str(actual[3]), "times": list(actual[4:8]),
            "sourceCount": actual[8], "valid": actual[9], "invalid": actual[10], "finalized": actual[11]}


def run_case(user, case, token):
    name, raw_base, offset, target_null, source, origin = case
    before = state(user)
    request = {"userId": user, "symbol": "BTCUSDT", "side": "SELL", "timezone": "UTC",
               "openLocal": "2026-09-26T17:00", "closeLocal": "2026-09-26T17:10",
               "openOffset": "", "closeOffset": "", "driver": "QUANTITY", "input": "0.01",
               "leverage": "10000", "walletEnabled": True, "historyEnabled": True}
    status, preview = api("POST", "/admin/orders/contract/manual/preview", request, token)
    assert status == 200 and preview["quotes"]["source"] == "binance", (name, status, preview.get("message"))
    history = preview["history"]
    assert history["baseSource"] == source
    assert history["sourceMinute"] == (None if source == "ZERO" else CLOSE + offset)
    expected_base = raw_base if source != "ZERO" else 0
    assert Decimal(str(history["base"])) == Decimal(expected_base)
    assert history["insertOrRepair"] is True
    net = Decimal(str(preview["calculation"]["net"]))
    assert net > 0
    request.update(previewToken=preview["previewToken"], idempotencyKey=uuid.uuid4().hex)
    status, created = api("POST", "/admin/orders/contract/manual", request, token)
    assert status == 200 and created.get("orderId"), (name, status, created.get("message"))
    after = state(user)
    assert after["orders"] == before["orders"] + 1 and after["audit"] == before["audit"] + 1
    old_available, old_frozen, old_version = before["wallet"].split("\t")
    available, frozen, version = after["wallet"].split("\t")
    assert Decimal(available) == Decimal(old_available) + net and frozen == old_frozen
    assert int(version) == int(old_version) + 1
    old_rows = [row.split("\t") for row in before["minutes"].splitlines() if row]
    rows = [row.split("\t") for row in after["minutes"].splitlines() if row]
    assert len(rows) == len(old_rows) + (0 if target_null else 1)
    assert all(row == old for row, old in zip(rows[:len(old_rows)], old_rows) if int(old[0]) < CLOSE)
    target = [row for row in rows if int(row[0]) == CLOSE]
    assert len(target) == 1
    assert Decimal(target[0][1]) == Decimal(expected_base) + net
    assert Decimal(target[0][2]) == net and target[0][3] == origin
    assert int(target[0][4]) == CLOSE + 60000
    assert after["counts"] == {"1m": len(rows), "1h": 1, "4h": 1, "1d": 1}
    impact = created["history"]
    assert impact["changedMinutes"] == 1 and impact["repairedParents"] == 3
    assert not impact["deferredPeriods"]
    parents = {grain: check_parent(user, grain, rows) for grain in ("1h", "4h", "1d")}
    return {"case": name, "userId": user, "orderId": int(created["orderId"]),
            "baseSource": source, "base": str(expected_base), "sourceMinute": history["sourceMinute"],
            "net": str(net), "targetValue": target[0][1], "targetOrigin": origin,
            "before": before, "after": after, "parents": parents}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--user-start", type=int, default=9000010)
    args = parser.parse_args()
    start = args.user_start
    assert 9000010 <= start <= 9000900, "Use only reserved isolated fixture user IDs"
    verify_target()
    assert sql("select count(*) from user_account where id=9000003 and email='manual-agent@local.invalid'") == "1"
    watermarks = sql(f"select task_name,watermark,user_cursor from asset_history_job_state where basis_version='{BASIS}' order by task_name")
    control = sql(f"select cast(net_equity as char) from asset_history_1m where user_id=9000002 and basis_version='{BASIS}'")
    seed(start)
    secrets = json.loads((ROOT / "reports/manual-order-browser/secrets.json").read_text(encoding="utf-8-sig"))
    status, login = api("POST", "/admin/auth/login", {"account": "manual-admin", "password": secrets["adminPassword"], "loginType": "email"})
    assert status == 200 and login.get("token")
    results = [run_case(start + i, case, login["token"]) for i, case in enumerate(CASES)]
    assert sql(f"select task_name,watermark,user_cursor from asset_history_job_state where basis_version='{BASIS}' order by task_name") == watermarks
    assert sql(f"select cast(net_equity as char) from asset_history_1m where user_id=9000002 and basis_version='{BASIS}'") == control
    output = {"passed": True, "isolatedUserRange": [start, start + len(CASES) - 1],
              "targetMinute": CLOSE, "watermarksUnchanged": True, "otherUserUnchanged": True,
              "cases": results}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "api-base-matrix.json").write_text(json.dumps(output, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"PASS {len(results)} real API 24h-base cases; orderIds={[x['orderId'] for x in results]}")


if __name__ == "__main__":
    main()
