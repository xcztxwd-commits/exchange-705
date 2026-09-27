"""Bundle Binance's own asset logos. Run with --container to use backend DNS.

Only exact Binance asset codes are matched; missing logos keep the existing fallback.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / 'exchange-backend/src/main/resources/binance-market-icons.zip'
ASSETS = 'https://www.binance.com/bapi/asset/v2/public/asset/asset/get-all-asset'
SPOT = 'https://data-api.binance.vision/api/v3/exchangeInfo?permissions=SPOT&showPermissionSets=false&symbolStatus=TRADING'
FUTURES = 'https://fapi.binance.com/fapi/v1/exchangeInfo'
LIMIT = 2 * 1024 * 1024


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', action='store_true')
    args = parser.parse_args()

    def fetch(url):
        if args.container:
            data = subprocess.run(['docker', 'compose', 'exec', '-T', 'backend',
                                   'curl', '-fsS', '--max-time', '25', '--max-filesize',
                                   str(12 * LIMIT), url], cwd=ROOT, check=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
        else:
            with urllib.request.urlopen(url, timeout=25) as response:
                data = response.read(12 * LIMIT + 1)
        if len(data) > 12 * LIMIT:
            raise ValueError('Response exceeds size limit')
        return data

    assets = json.loads(fetch(ASSETS))
    if assets.get('code') != '000000' or not assets.get('data'):
        raise ValueError('Binance did not return its asset directory')
    spot = [r for r in json.loads(fetch(SPOT))['symbols']
            if r['status'] == 'TRADING' and r.get('isSpotTradingAllowed')]
    futures = [r for r in json.loads(fetch(FUTURES))['symbols']
               if r['status'] == 'TRADING' and r.get('contractType') == 'PERPETUAL'
               and r.get('underlyingType') == 'COIN']
    wanted = {r['baseAsset'] for r in spot + futures}
    directory = {}
    for row in assets['data']:
        code, url = row['assetCode'], row.get('logoUrl')
        if code in wanted and re.fullmatch('[A-Z0-9._-]{1,40}', code) and url:
            parsed = urllib.parse.urlparse(url)
            if parsed.scheme == 'https' and parsed.hostname in {'bin.bnbstatic.com', 'ex.bnbstatic.com'}:
                directory[code] = {'url': url, 'name': row.get('assetName'), 'assetId': row['id']}

    def download(item):
        code, info = item
        data = fetch(info['url'])
        if data.startswith(b'\x89PNG\r\n\x1a\n'):
            mime = 'image/png'
        elif data.startswith(b'\xff\xd8\xff'):
            mime = 'image/jpeg'
        elif data.startswith(b'RIFF') and data[8:12] == b'WEBP':
            mime = 'image/webp'
        else:
            raise ValueError('Unsupported logo image: ' + code)
        if len(data) > LIMIT:
            raise ValueError('Logo exceeds size limit: ' + code)
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">'
               '<image width="64" height="64" href="data:' + mime + ';base64,'
               + base64.b64encode(data).decode('ascii') + '"/></svg>').encode()
        return code, svg, dict(info, sha256=hashlib.sha256(data).hexdigest(), mime=mime)

    files, logos = {}, {}
    # A failed download aborts before replacing the existing archive.
    with ThreadPoolExecutor(max_workers=6) as pool:
        for index, (code, svg, info) in enumerate(pool.map(download, sorted(directory.items())), 1):
            files['crypto/' + code.lower() + '.svg'] = svg
            logos[code] = info
            if index % 100 == 0:
                print('Downloaded', index, 'logos', flush=True)
    coverage = {}
    for name, rows in [('spot', spot), ('perpetual', futures)]:
        bases = {r['baseAsset'] for r in rows}
        coverage[name] = {'pairs': len(rows), 'coveredPairs': sum(r['baseAsset'] in logos for r in rows),
                          'assets': len(bases), 'coveredAssets': len(bases & logos.keys()),
                          'missingAssets': sorted(bases - logos.keys())}
    manifest = {'fetchedAt': datetime.now(timezone.utc).isoformat(), 'sources': [ASSETS, SPOT, FUTURES],
                'coverage': coverage, 'logos': logos,
                'rights': 'Binance-hosted asset logos; trademarks remain with their respective owners. Not CC0.'}
    files['sources.json'] = json.dumps(manifest, ensure_ascii=True, indent=2).encode()
    temporary = TARGET.with_suffix('.tmp')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            archive.writestr(name, data)
    temporary.replace(TARGET)
    print(json.dumps({'logos': len(logos), 'bytes': TARGET.stat().st_size, 'coverage': coverage}))


if __name__ == '__main__':
    main()
