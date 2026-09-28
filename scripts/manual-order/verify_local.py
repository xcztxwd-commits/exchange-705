"""Re-run HTTP/SQL acceptance against the owned, loopback-only browser fixture.

Requires the running exchange-705-manual-browser containers and its ignored secrets.json.
Never accepts a production URL or database. Tokens/passwords stay in memory.
"""
import json
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTAINER = "exchange-705-manual-browser-mysql"
BASE = "http://127.0.0.1:18151/api"
OUT = ROOT / "reports/manual-order-browser"


def sql(statement):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-c",
         'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -N -B -uroot manual_browser'],
        input=statement, text=True, encoding="utf-8", capture_output=True, check=True)
    return result.stdout.strip()


def call(method, path, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, headers=headers, method=method,
                                     data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def main():
    metadata = json.loads(subprocess.check_output(["docker", "inspect", CONTAINER], text=True))[0]
    assert metadata["Config"]["Labels"]["exchange-manual-browser"] == "true"
    assert all(binding["HostIp"] == "127.0.0.1"
               for bindings in metadata["NetworkSettings"]["Ports"].values()
               for binding in bindings or [])
    assert sql("select concat(database(),':',version())").startswith("manual_browser:5.7.")
    secrets = json.loads((OUT / "secrets.json").read_text(encoding="utf-8-sig"))
    # Dedicated role-test identities, copied only from this fixture's generated hash.
    for user_id, email, role in [(9000002, "manual-api@local.invalid", "user"),
                                 (9000003, "manual-agent@local.invalid", "agent")]:
        existing = sql(f"select email from user_account where id={user_id}")
        assert not existing or existing == email
        sql(f"insert into user_account(id,email,password_hash,status,user_type,row_version,created_at,updated_at) "
            f"select {user_id},'{email}',password_hash,'normal','{role}',0,UTC_TIMESTAMP(),UTC_TIMESTAMP() "
            f"from user_account where id=9000001 and email='manual-user@local.invalid' "
            f"and not exists(select 1 from user_account where id={user_id});")
        sql(f"insert into asset_account(user_id,coin,available,frozen,row_version,created_at,updated_at) "
            f"select {user_id},'CONTRACT',-50,0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP() "
            f"where not exists(select 1 from asset_account where user_id={user_id} and coin='CONTRACT');")

    evidence = []
    def check(name, condition, detail=None):
        evidence.append({"case": name, "passed": bool(condition), "detail": detail})
        assert condition, name

    def login(path, account, password):
        status, data = call("POST", path, {"account": account, "password": password, "loginType": "email"})
        assert status == 200 and data.get("token"), "fixture login failed"
        return data["token"]

    admin = login("/admin/auth/login", "manual-admin", secrets["adminPassword"])
    user = login("/auth/login", "manual-api@local.invalid", secrets["userPassword"])
    agent = login("/admin/auth/login", "manual-agent@local.invalid", secrets["userPassword"])
    path = "/admin/orders/contract/manual"
    for role, token in [("anonymous", None), ("user", user), ("agent", agent)]:
        for method, endpoint in [("GET", path + "/context"), ("POST", path + "/preview"), ("POST", path)]:
            status, _ = call(method, endpoint, None if method == "GET" else {}, token)
            check(f"{role} {method} {endpoint} rejected", status in (401, 403), status)

    saved = json.loads(sql("select evidence from manual_order_record where order_id=88"))
    request = saved["request"]
    before = sql("select count(*) from contract_order;select count(*) from manual_order_record;"
                 "select available from asset_account where user_id=9000001 and coin='CONTRACT';")
    status, data = call("POST", path, request, admin)
    check("durable replay after backend restart", status == 200 and data.get("orderId") == 88, data.get("orderId"))
    status, _ = call("POST", path, dict(request, input="0.15"), admin)
    check("same key different content rejected", status == 400, status)
    for name, updates in [
        ("history without wallet", {"walletEnabled": False, "historyEnabled": True}),
        ("forged source", {"orderSource": "USER"}),
        ("forged price", {"openPrice": 1}),
        ("forged profit", {"profit": 999999}),
        ("future minute", {"closeLocal": "2099-01-01T00:00"}),
        ("non minute input", {"closeLocal": "2026-09-27T17:10:01"}),
        ("missing exact historical quote", {"openLocal": "1970-01-01T00:01", "closeLocal": "1970-01-01T00:02", "previewToken": None}),
    ]:
        status, data = call("POST", path + "/preview", dict(request, **updates), admin)
        check(name, status == 400, {"status": status, "message": data.get("message")})
    status, data = call("POST", "/trade/contract/order",
                        {"symbol": "BTCUSDT", "side": "BUY", "type": "MARKET", "quantity": "0.01", "leverage": "100"}, user)
    check("ordinary order cannot spend negative balance", status == 400 and "余额不足" in data.get("message", ""), data.get("message"))
    status, data = call("GET", "/trade/contract/orders?status=CLOSED", token=user)
    check("ordinary API excludes internal audit", status == 200 and "operator_id" not in json.dumps(data) and "idempotencyKey" not in json.dumps(data))
    after = sql("select count(*) from contract_order;select count(*) from manual_order_record;"
                "select available from asset_account where user_id=9000001 and coin='CONTRACT';")
    check("replay and rejected requests leave orders/audit/wallet unchanged", before == after, after)
    (OUT / "http-acceptance.json").write_text(json.dumps(evidence, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"HTTP/SQL acceptance: {len(evidence)} passed, 0 failed, 0 skipped")


if __name__ == "__main__":
    main()
