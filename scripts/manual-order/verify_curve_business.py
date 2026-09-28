"""Real admin API + MySQL assertions on the retained isolated curve fixture.

No replacement pricing or business algorithm. Expected row changes are checked
against the service's quoted net amount and the saved pre-submit database rows.
"""
import json
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

from curve_fixture import BASIS, CONTAINER, ROOT, USER, at, sql, verify_target

BASE = "http://127.0.0.1:18151/api"
OUT = ROOT / "reports/manual-history-order-e2e"
CLOSE = at("2026-09-26T17:10:00")


def api(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, method=method, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def rows(table, columns, predicate):
    text = sql(f"select {columns} from {table} where {predicate} order by bucket_start")
    return [line.split("\t") for line in text.splitlines()] if text else []


def stamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def snapshot():
    return {
        "minute": rows("asset_history_1m", "bucket_start,coalesce(cast(net_equity as char),'NULL'),cast(manual_adjustment as char),origin,coalesce(effective_at,observed_at)", f"user_id={USER} and basis_version='{BASIS}'"),
        "parents": {name: rows(name, "bucket_start,open_value,high_value,low_value,close_value,open_at,high_at,low_at,close_at,source_count,valid_sample_count,invalid_sample_count,finalized", f"user_id={USER} and basis_version='{BASIS}'") for name in ("asset_history_1h", "asset_history_4h", "asset_history_1d")},
        "wallet": sql(f"select cast(available as char),cast(frozen as char),row_version from asset_account where user_id={USER} and coin='CONTRACT'").split("\t"),
        "watermarks": sql("select task_name,watermark,user_cursor from asset_history_job_state where basis_version='net_equity_v1' order by task_name"),
        "controls": sql("select user_id,basis_version,bucket_start,cast(net_equity as char) from asset_history_1m where (user_id=9000002 or basis_version='other_basis_v1') order by user_id,basis_version,bucket_start"),
        "orders": int(sql(f"select count(*) from contract_order where user_id={USER}")),
        "audit": int(sql(f"select count(*) from manual_order_record where user_id={USER}")),
    }


def check(before, after, net):
    bmin = {int(row[0]): row for row in before["minute"]}
    amin = {int(row[0]): row for row in after["minute"]}
    assert set(bmin) == set(amin)
    changed = 0
    for minute, old in bmin.items():
        new = amin[minute]
        if old[1] == "NULL" or minute < CLOSE:
            assert new == old, ("unchanged minute", minute, old, new)
            continue
        assert Decimal(new[1]) == Decimal(old[1]) + net, (minute, old, new)
        assert Decimal(new[2]) == Decimal(old[2]) + net
        assert new[3:] == old[3:]
        changed += 1
    assert changed == 5, changed
    assert Decimal(after["wallet"][0]) == Decimal(before["wallet"][0]) + net
    assert after["wallet"][1] == before["wallet"][1]
    assert int(after["wallet"][2]) == int(before["wallet"][2]) + 1
    assert after["watermarks"] == before["watermarks"]
    assert after["controls"] == before["controls"]
    assert after["orders"] == before["orders"] + 1
    assert after["audit"] == before["audit"] + 1
    for table, old_rows in before["parents"].items():
        old = {int(row[0]): row for row in old_rows}
        new = {int(row[0]): row for row in after["parents"][table]}
        assert set(new) == set(old), (table, "unexpected early parent")
        for start, row in new.items():
            if start < CLOSE and start + {"asset_history_1h": 3600000, "asset_history_4h": 14400000, "asset_history_1d": 86400000}[table] <= CLOSE:
                assert row == old[start]
            else:
                assert row[-4:] == old[start][-4:], (table, row, old[start])
                assert Decimal(row[4]) == Decimal(old[start][4]) + net, (table, row)
                assert row[0] == old[start][0]
                assert row[-1] == "1"
    # Explicit boundary, extrema timestamps, and NULL/zero preservation.
    first = {int(r[0]): r for r in after["parents"]["asset_history_1h"]}[at("2026-09-26T17:00:00")]
    vals = [(Decimal(r[1]), int(r[4])) for r in after["minute"] if r[1] != "NULL" and at("2026-09-26T17:00:00") <= int(r[0]) < at("2026-09-26T18:00:00")]
    assert Decimal(first[1]) == vals[0][0]
    assert Decimal(first[2]) == max(x[0] for x in vals)
    assert Decimal(first[3]) == min(x[0] for x in vals)
    assert int(first[6]) == min(t for v, t in vals if v == max(x[0] for x in vals))
    assert int(first[7]) == min(t for v, t in vals if v == min(x[0] for x in vals))


def main():
    verify_target()
    assert sql(f"select count(*) from asset_history_1d where user_id={USER}") == "1", "Run curve_fixture.py seed first"
    before = snapshot()
    secrets = json.loads((ROOT / "reports/manual-order-browser/secrets.json").read_text(encoding="utf-8-sig"))
    status, login = api("POST", "/admin/auth/login", {"account": "manual-admin", "password": secrets["adminPassword"], "loginType": "email"})
    assert status == 200 and login.get("token")
    token = login["token"]
    request = {"userId": USER, "symbol": "BTCUSDT", "side": "BUY", "timezone": "UTC",
               "openLocal": "2026-09-26T17:00", "closeLocal": "2026-09-26T17:10",
               "openOffset": "", "closeOffset": "", "driver": "QUANTITY", "input": "0.14",
               "leverage": "10000", "walletEnabled": True, "historyEnabled": True}
    for attempt in range(8):
        status, preview = api("POST", "/admin/orders/contract/manual/preview", request, token)
        if status == 200:
            break
        time.sleep(1)
    assert status == 200, (status, preview.get("message"))
    assert preview["quotes"]["source"] == "binance"
    net = Decimal(str(preview["calculation"]["net"]))
    assert net < 0
    request.update(previewToken=preview["previewToken"], idempotencyKey=uuid.uuid4().hex)
    sent = stamp()
    status, created = api("POST", "/admin/orders/contract/manual", request, token)
    responded = stamp()
    assert status == 200 and created.get("orderId"), (status, created.get("message"))
    after = snapshot()
    db_seen = stamp()
    check(before, after, net)
    assert created["history"]["changedMinutes"] == 5
    order_id = int(created["orderId"])
    assert sql(f"select status,order_source,manual_wallet_enabled,manual_equity_enabled from contract_order where id={order_id}") == "CLOSED\tMANUAL_TEST\t1\t1"
    status, replay = api("POST", "/admin/orders/contract/manual", request, token)
    assert status == 200 and int(replay["orderId"]) == order_id
    assert snapshot() == after
    status, _ = api("POST", "/admin/orders/contract/manual", dict(request, input="0.15"), token)
    assert status == 400 and snapshot() == after
    status, _ = api("POST", "/admin/orders/contract/manual/preview", dict(request, walletEnabled=False, historyEnabled=True), token)
    assert status == 400 and snapshot() == after
    evidence = {"case": "real API historical order + four tables", "passed": True, "orderId": order_id,
                "net": str(net), "marketSource": preview["quotes"]["source"],
                "openPrice": str(preview["quotes"]["openPrice"]), "closePrice": str(preview["quotes"]["closePrice"]),
                "requestSentUtc": sent, "successResponseUtc": responded, "dbFirstObservedCommittedUtc": db_seen,
                "changedMinutes": created["history"]["changedMinutes"],
                "repairedParents": created["history"]["repairedParents"],
                "deferredPeriods": created["history"]["deferredPeriods"],
                "checks": ["relative row delta once", "pre-close and NULL unchanged", "OHLC/extrema/counts", "watermarks/control rows unchanged", "wallet once/frozen unchanged", "durable idempotency", "same-key conflict", "illegal switches rejected"],
                "before": before, "after": after}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "business-create.json").write_text(json.dumps(evidence, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"PASS order={order_id} net={net} minutes=5 parents={evidence['repairedParents']} repeated=1 conflict=400 illegal=400")
    print(f"request={sent} response={responded} db_visible={db_seen}")


if __name__ == "__main__":
    main()
