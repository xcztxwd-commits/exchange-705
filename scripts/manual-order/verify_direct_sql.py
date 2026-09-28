"""Temporarily change one isolated history row; never repair a parent implicitly.

Usage: python verify_direct_sql.py mutate|restore 1m|1h|4h|1d
Always run restore after browser/API observations. The saved original is guarded.
"""
import json
import sys
from decimal import Decimal

from curve_fixture import BASIS, ROOT, USER, at, sql, verify_target

OUT = ROOT / "reports/manual-history-order-e2e"
ROWS = {
    "1m": ("asset_history_1m", "net_equity", at("2026-09-27T17:10:00")),
    "1h": ("asset_history_1h", "close_value", at("2026-09-27T17:00:00")),
    "4h": ("asset_history_4h", "close_value", at("2026-09-26T16:00:00")),
    "1d": ("asset_history_1d", "close_value", at("2026-09-26T00:00:00")),
}


def snapshot():
    return {grain: sql(f"select cast({column} as char) from {table} where user_id={USER} and basis_version='{BASIS}' and bucket_start={bucket}")
            for grain, (table, column, bucket) in ROWS.items()}


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("mutate", "restore") or sys.argv[2] not in ROWS:
        raise SystemExit(__doc__)
    action, grain = sys.argv[1:]
    verify_target()
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"direct-sql-{grain}.json"
    table, column, bucket = ROWS[grain]
    current = snapshot()
    assert all(v and v != "NULL" for v in current.values()), current
    if action == "mutate":
        assert not path.exists() or json.loads(path.read_text())["restored"], "Restore prior mutation first"
        original = current[grain]
        changed = str(Decimal(original) + Decimal("123.45"))
        sql(f"start transaction; update {table} set {column}={changed} where user_id={USER} and basis_version='{BASIS}' and bucket_start={bucket} and {column}={original}; commit;")
        after = snapshot()
        assert after[grain] == changed and all(after[g] == v for g, v in current.items() if g != grain), (current, after)
        evidence = {"grain": grain, "table": table, "bucketStart": bucket, "original": original,
                    "changed": changed, "before": current, "afterMutation": after, "restored": False}
        path.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    else:
        evidence = json.loads(path.read_text(encoding="utf-8"))
        assert evidence["grain"] == grain and not evidence["restored"] and current[grain] == evidence["changed"]
        sql(f"start transaction; update {table} set {column}={evidence['original']} where user_id={USER} and basis_version='{BASIS}' and bucket_start={bucket} and {column}={evidence['changed']}; commit;")
        after = snapshot()
        assert after == evidence["before"], (after, evidence["before"])
        evidence.update(afterRestore=after, restored=True)
        path.write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    print(f"PASS {action} {grain}: {evidence['original']} -> {evidence['changed']}; other grains unchanged; restored={evidence['restored']}")


if __name__ == "__main__":
    main()
