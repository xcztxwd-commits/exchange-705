"""Sample the running backend's quote path; no provider credentials required.

Usage: python scripts/market/check-quotes.py --base http://localhost:17052 --minutes 10
"""

import argparse
import csv
import json
import time
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


def request(base, path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {"Content-Type": "application/json"} if data else {}
    started = time.monotonic()
    with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers), timeout=8) as response:
        result = json.load(response)
    return result, round((time.monotonic() - started) * 1000)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:17052")
    parser.add_argument("--minutes", type=float, default=10)
    parser.add_argument("--every", type=float, default=5)
    parser.add_argument("--symbols", default="BTCUSDT,ETHUSDT,EURUSD=X,GBPUSD=X,JPY=X,AAPL")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    symbols = [symbol.strip() for symbol in args.symbols.split(",") if symbol.strip()]
    output = args.output or Path("reports") / f"market-stability-{datetime.now(timezone.utc):%Y%m%d-%H%M%S}.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    registry, _ = request(args.base, "/api/market/all")
    category = {item["symbol"]: item.get("sourceCategory") or item.get("category") for item in registry["list"]}
    counts = Counter()
    end = time.monotonic() + args.minutes * 60
    fields = ("at_utc", "symbol", "status", "transport", "price", "source_age_ms", "fetch_age_ms",
              "request_ms", "group_error", "group_failures", "stream_connected", "stream_error",
              "stream_messages", "stream_reconnects", "ws_observed_age_ms", "ws_source_lag_ms",
              "ws_queue_lag_ms", "ws_event_id", "error")
    with output.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=fields)
        writer.writeheader()
        while time.monotonic() < end:
            started = time.monotonic()
            at = datetime.now(timezone.utc).isoformat()
            try:
                status, status_ms = request(args.base, "/api/market/status")
                quotes, quote_ms = request(args.base, "/api/market/price/batch", {"symbols": symbols})
                groups = {group["category"]: group for group in status}
                now_ms = time.time() * 1000
                for symbol in symbols:
                    quote = quotes.get("data", {}).get(symbol, {})
                    group = groups.get(category.get(symbol), {})
                    stream = group.get("stream", {})
                    observation = stream.get("observations", {}).get(symbol, {})
                    row = dict(at_utc=at, symbol=symbol, status=quote.get("status", "missing"),
                               transport=quote.get("transport"), price=quote.get("price"),
                               source_age_ms=round(now_ms - quote["timestamp"]) if quote.get("timestamp") else "",
                               fetch_age_ms=round(now_ms - quote["fetchedAt"]) if quote.get("fetchedAt") else "",
                               request_ms=status_ms + quote_ms, group_error=group.get("error"),
                               group_failures=group.get("failures"), stream_connected=stream.get("connected"),
                               stream_error=stream.get("error"), stream_messages=stream.get("messages"),
                               stream_reconnects=stream.get("reconnects"),
                               ws_observed_age_ms=round(now_ms - observation["timestamp"]) if observation.get("timestamp") else "",
                               ws_source_lag_ms=observation["fetchedAt"] - observation["timestamp"]
                               if observation.get("fetchedAt") and observation.get("timestamp") else "",
                               ws_queue_lag_ms=observation.get("lastQueueLagMs", ""),
                               ws_event_id=observation.get("eventId", ""),
                               error="")
                    writer.writerow(row)
                    counts[(symbol, row["status"], row["transport"])] += 1
            except Exception as failure:
                writer.writerow(dict(at_utc=at, symbol="*", status="request_error", error=str(failure)))
                counts[("*", "request_error", "")] += 1
            file.flush()
            time.sleep(max(0, args.every - (time.monotonic() - started)))
    print(f"output={output.resolve()}")
    for key, count in sorted(counts.items()):
        print(*key, count, sep="\t")


if __name__ == "__main__":
    main()
