// Current builds and actual backend only. All sockets bind loopback; no response mocks.
const fs = require('node:fs'), https = require('node:https'), http = require('node:http'), path = require('node:path');
const root = path.resolve(__dirname, '../..'), home = path.resolve(process.argv[2] || '');
if (!home.startsWith(path.join(root, 'reports') + path.sep) || !path.basename(home).startsWith('stage1-')) throw new Error('Explicit stage1 run directory required');
const privateDir = path.join(home, 'private');
const frontend = path.join(home, 'frontend');
const spec = JSON.parse(fs.readFileSync(path.join(home, 'environment.json'),'utf8'));
const httpsPort = spec.httpsPort || 443;
if (spec.demoBackendPort != null && (!Number.isInteger(spec.demoBackendPort) || spec.demoBackendPort < 1024 || spec.demoBackendPort > 65535 || spec.demoBackendPort === spec.backendPort)) throw new Error('Separate owned DEMO backend port required');
if (!Number.isInteger(httpsPort) || httpsPort < 1024 && httpsPort !== 443 || httpsPort > 65535) throw new Error('Invalid loopback HTTPS port');
const roots = {'control.localhost':'control', 'admin.localhost':'admin',
  'a.localhost':'pc', 'b.localhost':'pc', 'a2.localhost':'pc', 'default.localhost':'pc'};
const mime = {'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.webp':'image/webp','.jpg':'image/jpeg','.woff2':'font/woff2','.json':'application/json'};
const server = https.createServer({key:fs.readFileSync(path.join(privateDir,'server.key')),cert:Buffer.concat([fs.readFileSync(path.join(privateDir,'server.crt')),fs.readFileSync(path.join(privateDir,'ca.crt'))])}, (req,res) => {
  const host = (req.headers.host || '').toLowerCase().replace(new RegExp(':' + httpsPort + '$'), '');
  if (!roots[host]) {res.writeHead(403); return res.end('Unknown local fixture host');}
  if (req.url.startsWith('/api/') || req.url.startsWith('/demo-api/') || req.url.startsWith('/demo-uploads/') || req.url === '/healthz') {
    const demo = req.url.startsWith('/demo-api/') || req.url.startsWith('/demo-uploads/');
    if (demo && !spec.demoBackendPort) {res.writeHead(503);return res.end('Dedicated DEMO backend not provisioned');}
    const headers = {...req.headers, host};
    // The gateway never forwards caller-supplied tenant selection or proxy identities.
    for (const key of ['x-forwarded-host','forwarded','x-tenant-id']) delete headers[key];
    const upstream = http.request({hostname:'127.0.0.1',port:demo ? spec.demoBackendPort : spec.backendPort,path:demo ? req.url.replace(/^\/demo-api\//,'/api/').replace(/^\/demo-uploads\//,'/api/uploads/') : req.url,method:req.method,headers}, reply => {
      res.writeHead(reply.statusCode,reply.headers); reply.pipe(res);
    });
    upstream.on('error',()=>{res.writeHead(502);res.end('Dedicated backend unavailable')});req.pipe(upstream);return;
  }
  let relative; try { relative=decodeURIComponent(req.url.split('?')[0]); } catch {res.writeHead(400);return res.end();}
  const mobile=relative==='/mobile'||relative.startsWith('/mobile/');
  const base=path.join(frontend,mobile?'mobile':roots[host]);
  if(mobile)relative=relative.slice(7)||'/';
  let file=path.resolve(base,'.'+relative);
  if(file!==base&&!file.startsWith(base+path.sep)){res.writeHead(403);return res.end();}
  if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(base,'index.html');
  if(!fs.existsSync(file)){res.writeHead(503);return res.end('Current frontend not built');}
  res.writeHead(200,{'Content-Type':mime[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});
  fs.createReadStream(file).pipe(res);
});
// Forward only the real market socket; host/origin/tenant authorization remains in the actual backend.
server.on('upgrade',(req,socket,head)=>{
 const host=(req.headers.host||'').toLowerCase().replace(new RegExp(':'+httpsPort+'$'),'');
 const demo=req.url==='/demo-api/ws/market';
 if (!roots[host] || !['/api/ws/market','/demo-api/ws/market'].includes(req.url) || demo&&!spec.demoBackendPort) {socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n');return;}
 const headers={...req.headers,host};for(const key of ['x-forwarded-host','forwarded','x-tenant-id'])delete headers[key];
 const upstream=http.request({hostname:'127.0.0.1',port:demo?spec.demoBackendPort:spec.backendPort,path:'/api/ws/market',method:'GET',headers});
 upstream.on('upgrade',(reply,remote,remoteHead)=>{
  socket.write('HTTP/1.1 101 Switching Protocols\r\n'+Object.entries(reply.headers).map(([k,v])=>k+': '+v+'\r\n').join('')+'\r\n');
  if(head.length)remote.write(head);if(remoteHead.length)socket.write(remoteHead);remote.pipe(socket);socket.pipe(remote);
  remote.on('error',()=>socket.destroy());socket.on('error',()=>remote.destroy());
 });
 upstream.on('response',reply=>{socket.end('HTTP/1.1 '+reply.statusCode+' Rejected\r\nConnection: close\r\n\r\n');reply.resume();});
 upstream.on('error',()=>socket.destroy());upstream.end();
});
server.listen(httpsPort,'127.0.0.1',()=>console.log('Stage-owned current-build HTTPS gateway ready on loopback:' + httpsPort));
const quoteInput = spec.stage2QuoteInput ? require('../stage2/quote-input.cjs').handler(home,spec) : (req,res)=>{res.writeHead(503);res.end('No public market provider in stage1 fixture')};
http.createServer(quoteInput).listen(33632,'127.0.0.1');

