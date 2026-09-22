"""Run Redis settlement-cache checks against the disposable isolation Redis."""
import json
import re
import subprocess
import time
import uuid
from pathlib import Path

source = (Path(__file__).resolve().parents[1] / 'exchange-backend/src/main/java/com/gtcfesk/exchange/market/RedisMarketService.java').read_text(encoding='utf-8')
block = source.split('new org.springframework.data.redis.core.script.DefaultRedisScript<>(', 1)[1].split('String.class);', 1)[0]
script = ''.join(json.loads(value) for value in re.findall(r'"(?:[^"\\]|\\.)*"', block))
key = 'test:settlement:' + str(uuid.uuid4())
base = ['docker', 'exec', 'exchange-705-isolation-redis-test-1', 'redis-cli', '--raw']

def run(*args):
    return subprocess.check_output(base + list(map(str, args)), text=True).strip()

def sample(price, timestamp):
    return json.dumps(dict(price=price, timestamp=timestamp, fetchedAt=timestamp))

def read(now, duration, candidate=''):
    value = run('EVAL', script, 1, key, now, duration, candidate)
    return json.loads(value) if value else None

now = int(time.time() * 1000)
try:
    first = read(now, 28800000, sample(0.0063, now - 60000))
    assert first['price'] == 0.0063
    assert 28700000 < int(run('PTTL', key)) <= 28800000
    assert read(now + 1000, 28800000, sample(0.007, now))['price'] == 0.0063
    assert read(now + 2000, 28800000)['price'] == 0.0063  # outage/restart retains snapshot
    shorter = read(now + 3000, 3600000)
    assert shorter['expiresAt'] == now - 60000 + 3600000
    assert read(now + 4000, 1000) is None  # shortening expires it; no fresh candidate
    refreshed = read(now + 4000, 3600000, sample(0.007, now + 4000))
    assert refreshed['price'] == 0.007
    assert read(now + 3604001, 3600000, sample(0.007, now + 4000)) is None
    print('PASS: Redis freeze, TTL, outage/restart reuse, duration change, refresh, stale rejection')
finally:
    run('DEL', key)
