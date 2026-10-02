// Reuse the reviewed gateway and supplier inputs. Only this owned stage5 run can start.
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module');
const root=path.resolve(__dirname,'../..'),home=path.resolve(process.argv[2]);
if(home!==path.join(root,'reports','stage5-final'))throw Error('Exact stage5 directory required');
const spec=JSON.parse(fs.readFileSync(path.join(home,'environment.json'),'utf8'));
const qp=path.join(root,'scripts/stage2/quote-input.cjs'),q=new Module(qp,module);q.filename=qp;q.paths=module.paths;q._compile(fs.readFileSync(qp,'utf8').replace('module.exports={handler};','module.exports={handler,reply};'),qp);
global.s5quotes=(req,res)=>{const v=q.exports.reply(req.url);res.writeHead(v===null?503:200,{'Content-Type':'application/json'});res.end(JSON.stringify(v));};
let source=fs.readFileSync(path.join(root,'scripts/stage1/gateway.cjs'),'utf8')
 .replace("!path.basename(home).startsWith('stage1-')","path.basename(home)!=='stage5-final'")
 .replace('const httpsPort = spec.httpsPort || 443;','Object.assign(spec,{httpsPort:spec.ports.https,backendPort:spec.ports.real,demoBackendPort:spec.ports.demo});const httpsPort=spec.httpsPort;')
 .replace("const mime =", "for(let i=0;i<10;i++)roots['s5-'+i+'.localhost']='pc';const mime =")
 .replace(/const quoteInput = spec.stage2QuoteInput[^\n]+/,'const quoteInput=global.s5quotes;')
 .replace("listen(33632,'127.0.0.1')","listen(spec.ports.quote,'127.0.0.1')")
 .replace("res.writeHead(502);res.end('Dedicated backend unavailable')","if(!res.headersSent)res.writeHead(502);if(!res.writableEnded)res.end('Dedicated backend unavailable')");
const gateway=new Module(__filename,module);gateway.filename=__filename;gateway.paths=module.paths;gateway._compile(source,__filename);
