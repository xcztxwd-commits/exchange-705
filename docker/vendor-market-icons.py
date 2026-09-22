"""Vendor pinned SVG resources. No runtime CDN dependency; retain upstream licenses."""
import io, tarfile, urllib.request, zipfile, pathlib, json, re, ast
ROOT=pathlib.Path(__file__).resolve().parents[1]
TARGET=ROOT/'exchange-backend/src/main/resources/market-icons.zip'
STOCKS={'AAPL':'apple','MSFT':'microsoft','GOOG':'google','GOOGL':'google','AMZN':'amazon','TSLA':'tesla','NVDA':'nvidia','META':'meta','NFLX':'netflix','AMD':'amd','INTC':'intel','IBM':'ibm','ORCL':'oracle','ADBE':'adobe','PYPL':'paypal','SPOT':'spotify','SHOP':'shopify','UBER':'uber','COIN':'coinbase','BABA':'alibabadotcom','SONY':'sony','TM':'toyota','NKE':'nike','SBUX':'starbucks','MCD':'mcdonalds','V':'visa','DIS':'waltdisney'}
SOURCES=[('cryptocurrency-icons','0.18.1'),('flag-icons','7.3.2'),('simple-icons','11.15.0'),('@web3icons/core','4.0.56')]
files={};counts={}
for package,version in SOURCES:
 url=f'https://registry.npmjs.org/{package}/-/{package.split("/")[-1]}-{version}.tgz'
 with urllib.request.urlopen(url,timeout=60) as r: archive=tarfile.open(fileobj=io.BytesIO(r.read()),mode='r:gz')
 for member in archive.getmembers():
  name=member.name;dest=None
  if name.lower().split('/')[-1] in ('license','license.md','license.txt'):dest=f'licenses/{package}.txt'
  elif package=='cryptocurrency-icons' and name.startswith('package/svg/color/') and name.endswith('.svg'):dest='crypto/'+name.split('/')[-1]
  elif package=='flag-icons' and name.startswith('package/flags/1x1/') and name.endswith('.svg'):dest='flags/'+name.split('/')[-1]
  elif package=='simple-icons' and name.startswith('package/icons/') and name.endswith('.svg'):
   brand=name.split('/')[-1][:-4]
   for ticker,wanted in STOCKS.items():
    if brand==wanted:files[f'stocks/{ticker}.svg']=archive.extractfile(member).read()
  elif package=='@web3icons/core' and name.startswith('package/dist/svgs/tokens/background/') and name.endswith('.svg.js'):
   code=name.split('/')[-1][:-7]
   if re.fullmatch('[A-Za-z0-9._-]+',code):
    module=archive.extractfile(member).read().decode()
    literal=re.search(r"=\s*('(?:\\.|[^'])*')",module,re.S)
    if literal:files['crypto/'+code.lower()+'.svg']=ast.literal_eval(literal.group(1)).encode()
  if dest:files[dest]=archive.extractfile(member).read()
with urllib.request.urlopen('https://raw.githubusercontent.com/0xa3k5/web3icons/f6881ce9606c86103cceac250b01df3f8867620d/LICENCE',timeout=30) as r:files['licenses/web3icons.txt']=r.read()
for name,data in list(files.items()):
 if name.endswith('.svg'):
  text=data.decode()
  text=re.sub(r'<svg\b[^>]*>',lambda m:re.sub(r'\s(?:width|height)="[^"]*"','',m.group()),text,count=1)
  files[name]=text.encode()
  assert '<svg' in text and not re.search(r'<(?:script|foreignObject)\b|\bon[a-z]+\s*=|(?:href)=[\"\'](?:https?:|javascript:)',text,re.I),name
  assert len(data)<250000,name
files['sources.json']=json.dumps({'packages':SOURCES,'stocks':STOCKS,'note':'Stock mappings identify listed companies; trademarks remain with their owners.'},indent=2).encode()
with zipfile.ZipFile(TARGET,'w',zipfile.ZIP_DEFLATED) as output:
 for name,data in sorted(files.items()):output.writestr(name,data)
print(json.dumps({'archive':str(TARGET),'bytes':TARGET.stat().st_size,'crypto':sum(k.startswith('crypto/') for k in files),'flags':sum(k.startswith('flags/') for k in files),'stocks':sum(k.startswith('stocks/') for k in files)}))
