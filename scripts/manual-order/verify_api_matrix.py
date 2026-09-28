"""Actual isolated admin API matrix, plus a zero-net order; no pricing reimplementation."""
import json
import uuid
from decimal import Decimal

from curve_fixture import ROOT, sql, verify_target
from verify_curve_business import OUT, api

USER = 9000004
SYMBOL = "BTCUSDT"
MINUTE = 1790442600000  # 2026-09-26 17:10 UTC


def state():
    def count(table):
        return int(sql(f"select count(*) from {table} where user_id={USER}"))
    return {
        "wallet": sql(f"select cast(available as char),cast(frozen as char),row_version from asset_account where user_id={USER} and coin='CONTRACT'"),
        "orders": count("contract_order"), "audit": count("manual_order_record"),
        "tables": {grain: count("asset_history_" + grain) for grain in ("1m", "1h", "4h", "1d")},
    }


def submit(token, wallet, history, side="SELL", same_minute=False):
    req = {"userId": USER, "symbol": SYMBOL, "side": side, "timezone": "UTC",
           "openLocal": "2026-09-26T17:10" if same_minute else "2026-09-26T17:00",
           "closeLocal": "2026-09-26T17:10", "openOffset": "", "closeOffset": "",
           "driver": "QUANTITY", "input": "0.01", "leverage": "10000",
           "walletEnabled": wallet, "historyEnabled": history}
    status, preview = api("POST", "/admin/orders/contract/manual/preview", req, token)
    assert status == 200, (status, preview.get("message"))
    assert preview["quotes"]["source"] == "binance"
    net = Decimal(str(preview["calculation"]["net"]))
    req.update(previewToken=preview["previewToken"], idempotencyKey=uuid.uuid4().hex)
    status, created = api("POST", "/admin/orders/contract/manual", req, token)
    assert status == 200 and created.get("orderId"), (status, created.get("message"))
    return int(created["orderId"]), net, created.get("history")


def main():
    verify_target()
    assert sql(f"select email from user_account where id={USER}") == "manual-slider@local.invalid"
    before = state()
    assert before["orders"] == 0 and all(v == 0 for v in before["tables"].values()), "Use a fresh isolated matrix account"
    secrets = json.loads((ROOT / "reports/manual-order-browser/secrets.json").read_text(encoding="utf-8-sig"))
    status, login = api("POST", "/admin/auth/login", {"account": "manual-admin", "password": secrets["adminPassword"], "loginType": "email"})
    assert status == 200 and login.get("token")
    token = login["token"]
    cases = []
    prior = before
    for wallet, history in ((False, False), (True, False), (True, True)):
        order, net, impact = submit(token, wallet, history)
        assert net > 0, net
        after = state()
        assert Decimal(after["wallet"].split("\t")[0]) == Decimal(prior["wallet"].split("\t")[0]) + (net if wallet else 0)
        assert after["orders"] == prior["orders"] + 1 and after["audit"] == prior["audit"] + 1
        assert after["tables"] == prior["tables"] if not history else after["tables"]["1m"] == 1
        if history:
            history_value, origin = sql(f"select cast(net_equity as char),origin from asset_history_1m where user_id={USER} and basis_version='net_equity_v1' and bucket_start={MINUTE}").split("\t")
            assert Decimal(history_value) == net and origin == "MANUAL_ZERO"
            assert all(after["tables"][grain] == 1 for grain in ("1h", "4h", "1d"))
        cases.append({"walletEnabled": wallet, "historyEnabled": history, "orderId": order, "net": str(net), "before": prior, "after": after, "historyImpact": impact})
        prior = after
    invalid = {"userId": USER, "symbol": SYMBOL, "side": "SELL", "timezone": "UTC",
               "openLocal": "2026-09-26T17:00", "closeLocal": "2026-09-26T17:10",
               "driver": "QUANTITY", "input": "0.01", "leverage": "10000", "walletEnabled": False, "historyEnabled": True}
    status, _ = api("POST", "/admin/orders/contract/manual/preview", invalid, token)
    assert status == 400 and state() == prior

    # Same-minute gross is zero. Temporarily set this isolated symbol's fee to
    # zero, then restore it even if the API call or assertion fails.
    original = sql(f"select id,cast(fee_multiplier as char),row_version from trading_symbol where symbol='{SYMBOL}'").split("\t")
    assert len(original) == 3
    symbol_id, fee, version = original
    try:
        sql(f"update trading_symbol set fee_multiplier=0,row_version=row_version+1 where id={symbol_id} and row_version={version}")
        next_fee, next_version = sql(f"select cast(fee_multiplier as char),row_version from trading_symbol where id={symbol_id}").split("\t")
        assert Decimal(next_fee) == 0 and int(next_version) == int(version) + 1
        zero_order, zero_net, impact = submit(token, True, True, side="BUY", same_minute=True)
        assert zero_net == 0
        zero_state = state()
        assert zero_state["wallet"].split("\t")[0] == prior["wallet"].split("\t")[0]
        assert zero_state["orders"] == prior["orders"] + 1 and zero_state["audit"] == prior["audit"] + 1
        assert zero_state["tables"] == prior["tables"]
    finally:
        sql(f"update trading_symbol set fee_multiplier={fee},row_version={version} where id={symbol_id} and row_version={int(version)+1}")
        assert sql(f"select cast(fee_multiplier as char),row_version from trading_symbol where id={symbol_id}") == f"{fee}\t{version}"
    result = {"passed": True, "userId": USER, "cases": cases, "illegalStatus": status,
              "zeroNet": {"orderId": zero_order, "net": str(zero_net), "after": zero_state, "historyImpact": impact},
              "symbolFeeRestored": True, "final": state()}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "api-switch-matrix.json").write_text(json.dumps(result, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    print(f"PASS 3 legal API combinations + illegal 400 + positive net + zero net; orders={[x['orderId'] for x in cases] + [zero_order]}; fee restored")


if __name__ == "__main__":
    main()
