"""Seed only the retained, loopback-only manual-order browser database.

Run after taking a database dump. Rows are synthetic equity observations, not
historical market quotes. The real manual-order API still obtains market quotes.
"""
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTAINER = "exchange-705-manual-browser-mysql"
USER = 9000001
BASIS = "net_equity_v1"


def at(iso):
    return int(datetime.fromisoformat(iso).replace(tzinfo=timezone.utc).timestamp() * 1000)


def sql(statement):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot manual_browser'],
        input=statement, text=True, capture_output=True, check=True,
    )
    return result.stdout.strip()


def verify_target():
    info = json.loads(subprocess.check_output(["docker", "inspect", CONTAINER], text=True))[0]
    assert info["Config"]["Labels"].get("exchange-manual-browser") == "true"
    assert info["State"]["Running"]
    assert all(b["HostIp"] == "127.0.0.1" for ports in info["NetworkSettings"]["Ports"].values() for b in ports or [])
    assert sql("select concat(database(),':',version())").startswith("manual_browser:5.7.")
    assert (ROOT / "rollback/manual-history-order-e2e-20260928/database-before.sql").stat().st_size > 10000
    assert sql(f"select email from user_account where id={USER}") == "manual-user@local.invalid"


def seed():
    verify_target()
    start = at("2026-09-26T17:00:00")
    if sql(f"select count(*) from asset_history_1m where user_id={USER} and basis_version='{BASIS}' and bucket_start>={start} and bucket_start<{start + 3600000}") != "0":
        raise SystemExit("Fixture hour already populated; refusing to overwrite it")
    assert sql("select count(*) from asset_history_job_state where task_name in ('rollup_1','rollup_2')") == "0"
    created = int(time.time() * 1000)
    rows = []
    for minute, value in [(7, None), (8, 0), (9, 1000), (10, 1000), (59, 1050)]:
        bucket = start + minute * 60000
        amount = "NULL" if value is None else str(value)
        status = "FAILED" if value is None else "ESTIMATED"
        rows.append(f"({USER},'{BASIS}',{bucket},{bucket + 60000},{amount},'{status}','E2E_FIXTURE','{{}}','MANUAL_CARRY',{created})")
    observation = ("insert into asset_history_1m(user_id,basis_version,bucket_start,effective_at,net_equity,"
                   "valuation_status,reason_code,valuation_evidence,origin,created_at) values " + ",".join(rows) + ";")
    low_at = start + 9 * 60000
    high_at = start + 3600000
    parent = []
    for table, bucket, end, expected, sources in [
        ("asset_history_1h", start, start + 3600000, 60, 5),
        ("asset_history_4h", at("2026-09-26T16:00:00"), at("2026-09-26T20:00:00"), 240, 1),
        ("asset_history_1d", at("2026-09-26T00:00:00"), at("2026-09-27T00:00:00"), 1440, 1),
    ]:
        parent.append(f"insert into {table}(user_id,basis_version,bucket_start,bucket_end,open_value,high_value,low_value,close_value,"
                      "open_at,high_at,low_at,close_at,source_count,valid_sample_count,invalid_sample_count,expected_sample_count,"
                      f"finalized,quality,source_through,updated_at) values ({USER},'{BASIS}',{bucket},{end},0,1050,0,1050,"
                      f"{low_at},{high_at},{low_at},{high_at},{sources},4,1,{expected},1,'PARTIAL',{high_at},{created});")
    controls = []
    for user, basis, value in [(9000002, BASIS, 777), (USER, "other_basis_v1", 888)]:
        bucket = start + 10 * 60000
        controls.append(f"insert into asset_history_1m(user_id,basis_version,bucket_start,effective_at,net_equity,valuation_status,"
                        f"reason_code,valuation_evidence,origin,created_at) values ({user},'{basis}',{bucket},{bucket + 60000},"
                        f"{value},'ESTIMATED','E2E_CONTROL','{{}}','MANUAL_CARRY',{created});")
    state = ("insert into asset_history_job_state(task_name,basis_version,watermark,user_cursor,success_at) values "
             f"('rollup_1','{BASIS}',{at('2026-09-27T19:00:00')},0,{created}),"
             f"('rollup_2','{BASIS}',{at('2026-09-27T16:00:00')},0,{created});")
    sql("start transaction;" + observation + "".join(parent + controls) + state + "commit;")
    assert sql(f"select count(*) from asset_history_1m where user_id={USER} and basis_version='{BASIS}' and bucket_start>={start} and bucket_start<{start + 3600000}") == "5"
    print("Seeded 5 synthetic minutes, 3 finalized parents, 2 control rows, 2 watermarks; target manual_browser only")


if __name__ == "__main__":
    if sys.argv[1:] != ["seed"]:
        raise SystemExit("Usage: python curve_fixture.py seed")
    seed()
