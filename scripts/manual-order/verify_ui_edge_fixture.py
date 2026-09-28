"""Temporary isolated 1D-only visual fixture for zero/NULL/sparse sign changes.

Usage: python verify_ui_edge_fixture.py seed|restore
"""
import json
import sys
import time

from curve_fixture import BASIS, ROOT, USER, at, sql, verify_target

OUT = ROOT / "reports/manual-history-order-e2e/ui-edge-fixture.json"
BASE = at("2026-09-27T16:00:00")
MINUTES = [BASE + i * 60000 for i in range(4)]
PREDICATE = f"user_id={USER} and basis_version='{BASIS}' and bucket_start in ({','.join(map(str, MINUTES))})"


def parents():
    return {grain: sql(f"select bucket_start,open_value,high_value,low_value,close_value,source_count from asset_history_{grain} where user_id={USER} and basis_version='{BASIS}' order by bucket_start")
            for grain in ("1h", "4h", "1d")}


def main():
    if sys.argv[1:] not in (["seed"], ["restore"]):
        raise SystemExit(__doc__)
    verify_target()
    if sys.argv[1] == "seed":
        assert sql(f"select count(*) from asset_history_1m where {PREDICATE}") == "0", "Refusing to overwrite existing history"
        before = parents()
        now = int(time.time() * 1000)
        values = ["1000", "0", "NULL", "-500"]
        rows = []
        for bucket, amount in zip(MINUTES, values):
            status = "FAILED" if amount == "NULL" else "ESTIMATED"
            rows.append(f"({USER},'{BASIS}',{bucket},{bucket+60000},{amount},'{status}','E2E_VIEW','{{}}','E2E_VIEW_FIXTURE',{now})")
        sql("start transaction;insert into asset_history_1m(user_id,basis_version,bucket_start,effective_at,net_equity,valuation_status,reason_code,valuation_evidence,origin,created_at) values " + ",".join(rows) + ";commit;")
        assert sql(f"select count(*) from asset_history_1m where {PREDICATE} and origin='E2E_VIEW_FIXTURE'") == "4"
        assert parents() == before, "Direct SQL must not repair parent buckets"
        OUT.write_text(json.dumps({"minutes": MINUTES, "values": values, "parentsBefore": before, "restored": False}, indent=2), encoding="utf-8")
    else:
        evidence = json.loads(OUT.read_text(encoding="utf-8"))
        assert not evidence["restored"] and evidence["minutes"] == MINUTES
        assert sql(f"select count(*) from asset_history_1m where {PREDICATE} and origin='E2E_VIEW_FIXTURE'") == "4"
        sql(f"start transaction;delete from asset_history_1m where {PREDICATE} and origin='E2E_VIEW_FIXTURE';commit;")
        assert sql(f"select count(*) from asset_history_1m where {PREDICATE}") == "0"
        assert parents() == evidence["parentsBefore"]
        evidence["restored"] = True
        OUT.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print("PASS UI edge fixture " + sys.argv[1] + ": isolated 1m only; parent buckets unchanged")


if __name__ == "__main__":
    main()
