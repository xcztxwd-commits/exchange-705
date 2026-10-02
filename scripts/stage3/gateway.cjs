// Reuse the reviewed loopback gateway; adapt ownership, ports and supplier-input contract only.
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module');
const root=path.resolve(__dirname,'../..'),home=path.resolve(process.argv[2]);
if(home!==path.join(root,'reports','stage3-20261002-232106'))throw Error('Exact Stage3 directory required');
const spec=JSON.parse(fs.readFileSync(path.join(home,'environment.json'),'utf8'));
const quotePath=path.join(root,'scripts/stage2/quote-input.cjs');
const quotes=new Module(quotePath,module);quotes.filename=quotePath;quotes.paths=module.paths;
quotes._compile(fs.readFileSync(quotePath,'utf8').replace('module.exports={handler};','module.exports={handler,reply};'),quotePath);
global.stage3Quotes=(req,res)=>{try{const value=quotes.exports.reply(req.url);res.writeHead(value===null?503:200,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));}catch{res.writeHead(400);res.end();}};
const original=path.join(root,'scripts/stage1/gateway.cjs');let source=fs.readFileSync(original,'utf8');
source=source.replace("!path.basename(home).startsWith('stage1-')","path.basename(home)!=='stage3-20261002-232106'")
 .replace('const httpsPort = spec.httpsPort || 443;',`Object.assign(spec,{httpsPort:spec.ports.https,backendPort:spec.ports.real,demoBackendPort:spec.ports.demo});const httpsPort=spec.httpsPort;`)
 .replace(/const quoteInput = spec.stage2QuoteInput[^\n]+/, 'const quoteInput=global.stage3Quotes;')
 .replace("listen(33632,'127.0.0.1')","listen(spec.ports.quote,'127.0.0.1')")
 .replace("upstream.on('error',()=>{res.writeHead(502);res.end('Dedicated backend unavailable')});", "upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);if(!res.writableEnded)res.end('Dedicated backend unavailable')});");
const gateway=new Module(__filename,module);gateway.filename=__filename;gateway.paths=module.paths;gateway._compile(source,__filename);
