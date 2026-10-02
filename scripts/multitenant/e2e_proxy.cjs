// Local synthetic TLS gateway. No credential logging, no production upstreams, no host fallback.
const fs=require('node:fs'), https=require('node:https'), http=require('node:http'), path=require('node:path');
const root=path.resolve(__dirname,'../..'), secret=path.join(root,'rollback/multitenant-20260929/schema/e2e');
const roots={'control.localhost':'exchange-admin/dist-control-e2e','admin.localhost':'exchange-admin/dist-e2e','a.localhost':'exchange-pc/dist-e2e','b.localhost':'exchange-pc/dist-e2e','default.localhost':'exchange-pc/dist-e2e'};
const types={'.html':'text/html; charset=utf-8','.js':'text/javascript','.css':'text/css','.svg':'image/svg+xml','.png':'image/png','.webp':'image/webp','.jpg':'image/jpeg','.woff2':'font/woff2','.json':'application/json'};
const server=https.createServer({key:fs.readFileSync(path.join(secret,'server.key')),cert:fs.readFileSync(path.join(secret,'server.crt'))},(req,res)=>{
  const host=(req.headers.host||'').toLowerCase();
  if(!roots[host]){res.writeHead(403);return res.end('Unknown test host');}
  if(req.url.startsWith('/api/')||req.url==='/healthz'){
    const headers={...req.headers,host};delete headers['x-forwarded-host'];delete headers['forwarded'];delete headers['x-tenant-id'];
    const upstream=http.request({hostname:'127.0.0.1',port:64031,path:req.url,method:req.method,headers},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res)});
    upstream.on('error',()=>{res.writeHead(502);res.end('Isolated backend unavailable')});req.pipe(upstream);return;
  }
  let relative;try{relative=decodeURIComponent(req.url.split('?')[0]);}catch{res.writeHead(400);return res.end();}
  const mobile=relative==='/mobile'||relative.startsWith('/mobile/');
  const base=path.join(root,mobile?'exchange-frontend/dist-e2e':roots[host]);
  if(mobile)relative=relative.slice(7)||'/';
  let file=path.resolve(base,'.'+relative);
  if(!file.startsWith(base+path.sep)&&file!==base){res.writeHead(403);return res.end();}
  if(!fs.existsSync(file)||fs.statSync(file).isDirectory())file=path.join(base,'index.html');
  if(!fs.existsSync(file)){res.writeHead(503);return res.end('Test frontend not built');}
  res.writeHead(200,{'Content-Type':types[path.extname(file)]||'application/octet-stream','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});fs.createReadStream(file).pipe(res);
});
server.listen(443,'127.0.0.1',()=>console.log('Synthetic HTTPS gateway listening on localhost:443'));
