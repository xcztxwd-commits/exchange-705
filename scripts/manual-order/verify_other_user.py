"""Read-only API control: another isolated user's history after manual orders."""
import json

from curve_fixture import ROOT, sql, verify_target
from verify_curve_business import OUT, api


def main():
    verify_target()
    assert sql("select cast(net_equity as char) from asset_history_1m where user_id=9000002 and basis_version='net_equity_v1' and bucket_start=1790442600000") == "777.0000000000000000"
    secrets = json.loads((ROOT / "reports/manual-order-browser/secrets.json").read_text(encoding="utf-8-sig"))
    status, login = api("POST", "/auth/login", {"account": "manual-api@local.invalid", "password": secrets["userPassword"], "loginType": "email"})
    assert status == 200 and login.get("user", {}).get("id") == 9000002 and login.get("token")
    token = login["token"]
    result = {"userId": 9000002, "controlMinute": "777.0000000000000000", "ranges": {}}
    for grain, table in (("1D", "asset_history_1m"), ("1W", "asset_history_1h"), ("1M", "asset_history_4h"), ("1Y", "asset_history_1d")):
        status, body = api("GET", "/user/asset-history?range=" + grain, token=token)
        assert status == 200 and body["sourceTable"] == table and body["userId"] == 9000002
        result["ranges"][grain] = {"sourceTable": table, "pointCount": len(body["points"]),
                                    "total": body["total"], "carryIn": body.get("carryIn"),
                                    "observedControlPoint": any(str(p.get("value", "")).startswith("777.") for p in body["points"])}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "other-user-api.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("PASS other user isolated control minute unchanged; four read paths return only user 9000002")


if __name__ == "__main__":
    main()
