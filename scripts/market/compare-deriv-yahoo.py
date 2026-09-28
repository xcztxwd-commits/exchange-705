"""Read-only, simultaneous FX tick comparison; Python websockets >=17."""
import asyncio
import base64
import json
import math
import statistics
import struct
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

from websockets.asyncio.client import connect

PAIRS = {
    "EURUSD": ("frxEURUSD", "EURUSD=X"),
    "GBPUSD": ("frxGBPUSD", "GBPUSD=X"),
    "USDJPY": ("frxUSDJPY", "JPY=X"),
}
DERIV = "wss://api.derivws.com/trading/v1/options/ws/public"
YAHOO_LOCAL = "ws://localhost:17052/api/ws/market"
YAHOO_DIRECT = "wss://streamer.finance.yahoo.com/?version=2"
DURATION = 90


def yahoo_pricing(encoded):
    """Read id, price, time from the repository's yahoo-pricing.proto wire format."""
    payload = base64.b64decode(encoded, validate=True)

    def varint(pos):
        number = shift = 0
        while pos < len(payload):
            byte = payload[pos]
            pos += 1
            number |= (byte & 127) << shift
            if not byte & 128:
                return number, pos
            shift += 7
            if shift >= 70:
                raise ValueError("protobuf varint too long")
        raise ValueError("truncated protobuf varint")

    result = {}
    pos = 0
    while pos < len(payload):
        tag, pos = varint(pos)
        field, wire = tag >> 3, tag & 7
        if field == 0:
            raise ValueError("invalid protobuf field")
        if wire == 0:
            value, pos = varint(pos)
            if field == 3:
                source = (value >> 1) ^ -(value & 1)
                result["source_ms"] = source * 1000 if source < 10_000_000_000 else source
        elif wire == 5:
            if pos + 4 > len(payload):
                raise ValueError("truncated protobuf float")
            if field == 2:
                result["price"] = struct.unpack_from("<f", payload, pos)[0]
            pos += 4
        elif wire == 2:
            length, pos = varint(pos)
            if pos + length > len(payload):
                raise ValueError("truncated protobuf bytes")
            if field == 1:
                result["symbol"] = payload[pos:pos + length].decode("utf-8")
            pos += length
        elif wire == 1:
            if pos + 8 > len(payload):
                raise ValueError("truncated protobuf double")
            pos += 8
        else:
            raise ValueError(f"unsupported protobuf wire type {wire}")
    if not {"symbol", "price", "source_ms"} <= result.keys():
        raise ValueError("Yahoo pricing message missing required fields")
    return result


def quantiles(values):
    a = sorted(x for x in values if isinstance(x, (int, float)) and math.isfinite(x))
    if not a:
        return {"n": 0, "p50": None, "p95": None, "max": None}
    return {"n": len(a), "p50": statistics.median(a), "p95": a[math.ceil(.95 * len(a)) - 1], "max": a[-1]}


def summarize(rows, started, ended, accepted, yahoo_source="yahoo_local"):
    seconds = (ended - started) / 1000
    result = {"duration_s": seconds, "accepted_deriv": accepted, "pairs": {}}
    for pair, (ds, ys) in PAIRS.items():
        d = [x for x in rows if x.get("source") == "deriv" and x.get("symbol") == ds and started <= x["recv_ms"] < ended]
        y = [x for x in rows if x.get("source") == yahoo_source and x.get("symbol") == ys and started <= x["recv_ms"] < ended]
        unique_y = list({x.get("version"): x for x in y if x.get("version") is not None}.values())
        unique_y.sort(key=lambda x: x["recv_ms"])
        aligned = []
        ys_by_time = sorted((x for x in y if x.get("status") == "available" and x.get("transport") == "ws"), key=lambda x: x["recv_ms"])
        for x in d:
            if not ys_by_time:
                break
            candidate = min(ys_by_time, key=lambda a: abs(a["recv_ms"] - x["recv_ms"]))
            if abs(candidate["recv_ms"] - x["recv_ms"]) <= 1500 and candidate.get("price") and x.get("price"):
                aligned.append((x["price"] - candidate["price"]) / candidate["price"] * 10000)
        result["pairs"][pair] = {
            "deriv_ticks": len(d), "deriv_hz": len(d) / seconds, "deriv_age_ms": quantiles(x["recv_ms"] - x["source_ms"] for x in d),
            "deriv_receive_interval_ms": quantiles(d[i]["recv_ms"] - d[i-1]["recv_ms"] for i in range(1, len(d))),
            "yahoo_frames": len(y), "yahoo_unique_versions": len(unique_y), "yahoo_unique_hz": len(unique_y) / seconds,
            "yahoo_receive_age_ms": quantiles(x["recv_ms"] - x["source_ms"] for x in y),
            "yahoo_upstream_age_ms": quantiles(x["fetched_ms"] - x["source_ms"] for x in unique_y if x.get("fetched_ms")),
            "yahoo_unique_receive_interval_ms": quantiles(unique_y[i]["recv_ms"] - unique_y[i-1]["recv_ms"] for i in range(1, len(unique_y))),
            "yahoo_statuses": {s: sum(x.get("status") == s for x in y) for s in sorted({x.get("status") for x in y})},
            "yahoo_transports": {s: sum(x.get("transport") == s for x in y) for s in sorted({x.get("transport") for x in y})},
            "aligned_price_difference_bps": quantiles(aligned),
        }
    return result


async def main():
    direct = "--direct-yahoo" in sys.argv
    yahoo_endpoint = YAHOO_DIRECT if direct else YAHOO_LOCAL
    out = Path("reports") / (("deriv-yahoo-direct-fx-" if direct else "deriv-yahoo-fx-") + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    out.mkdir(parents=True, exist_ok=False)
    rows, errors, accepted = [], [], []
    raw = (out / "events.ndjson").open("w", encoding="utf-8", buffering=1)

    def record(source, data):
        item = {"recv_ms": time.time_ns() / 1e6, "source": source, "data": data}
        raw.write(json.dumps(item, ensure_ascii=False) + "\n")
        return item["recv_ms"]

    async def yahoo_reader(ws):
        async for message in ws:
            data = json.loads(message)
            received = record("yahoo_direct" if direct else "yahoo_local", data)
            if direct:
                if data.get("type") != "pricing":
                    continue
                quote = yahoo_pricing(data["message"])
                if quote["symbol"] in {v[1] for v in PAIRS.values()}:
                    rows.append({"source": "yahoo_direct", "symbol": quote["symbol"], "recv_ms": received,
                        "source_ms": quote["source_ms"], "fetched_ms": received,
                        "price": quote["price"], "version": (quote["source_ms"], quote["price"]),
                        "status": "available", "transport": "ws"})
                continue
            if data.get("type") != "price":
                continue
            for symbol, quote in data.get("data", {}).items():
                if symbol not in {v[1] for v in PAIRS.values()}:
                    continue
                rows.append({"source": "yahoo_local", "symbol": symbol, "recv_ms": received,
                    "source_ms": quote.get("timestamp"), "fetched_ms": quote.get("fetchedAt"),
                    "price": quote.get("price"), "version": quote.get("quoteVersion"),
                    "status": quote.get("status"), "transport": quote.get("transport")})

    def deriv_message(data):
        received = record("deriv", data)
        if data.get("error"):
            errors.append({"req_id": data.get("req_id"), **data["error"]})
        if tick := data.get("tick"):
            rows.append({"source": "deriv", "symbol": tick["symbol"], "recv_ms": received,
                "source_ms": tick["epoch"] * 1000, "price": tick["quote"],
                "bid": tick.get("bid"), "ask": tick.get("ask")})

    started = ended = None
    try:
        async with connect(yahoo_endpoint, open_timeout=15) as yw, connect(DERIV, open_timeout=20) as dw:
            yahoo_symbols = [v[1] for v in PAIRS.values()]
            await yw.send(json.dumps({"subscribe": yahoo_symbols} if direct else
                {"action": "subscribe", "symbols": yahoo_symbols, "fastSymbols": yahoo_symbols}))
            reader = asyncio.create_task(yahoo_reader(yw))
            try:
                for req_id, symbol in enumerate((v[0] for v in PAIRS.values()), 1):
                    await dw.send(json.dumps({"ticks": symbol, "subscribe": 1, "req_id": req_id}))
                    while True:
                        data = json.loads(await asyncio.wait_for(dw.recv(), timeout=15))
                        deriv_message(data)
                        if data.get("req_id") == req_id:
                            if data.get("tick") and data.get("subscription", {}).get("id"):
                                accepted.append(symbol)
                            break
                    if errors:
                        break  # Respect service limit; do not retry or spread requests over more sockets.
                    await asyncio.sleep(1.5)
                if accepted:
                    await asyncio.sleep(3)
                    started = time.time_ns() / 1e6
                    deadline = time.monotonic() + DURATION
                    while (left := deadline - time.monotonic()) > 0:
                        try:
                            deriv_message(json.loads(await asyncio.wait_for(dw.recv(), timeout=left)))
                        except asyncio.TimeoutError:
                            break
                    ended = time.time_ns() / 1e6
            finally:
                reader.cancel()
                try:
                    await reader
                except asyncio.CancelledError:
                    pass
    except Exception as exc:
        errors.append({"local": repr(exc)})
    finally:
        raw.close()
        summary = {"started_utc": datetime.fromtimestamp(started / 1000, timezone.utc).isoformat() if started else None,
            "endpoint_deriv": DERIV, "endpoint_yahoo": yahoo_endpoint, "errors": errors,
            "note": "Both providers direct from same local machine." if direct else
                "Deriv direct socket vs Yahoo via existing local backend; not equivalent network paths or executable-price comparison."}
        if started and ended:
            summary.update(summarize(rows, started, ended, accepted, "yahoo_direct" if direct else "yahoo_local"))
        (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(out.resolve())
        print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    assert quantiles([1, 2, 3])["p50"] == 2
    assert yahoo_pricing(base64.b64encode(b"\x0a\x01A\x15" + struct.pack("<f", 1.25) + b"\x18\x02").decode()) == {"symbol": "A", "price": 1.25, "source_ms": 1000}
    asyncio.run(main())
