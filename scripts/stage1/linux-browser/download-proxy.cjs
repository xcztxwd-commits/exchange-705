// Build-only CONNECT tunnel. Clients still validate vendor TLS, APT signatures and Node SHA; no TLS termination.
const http=require('node:http'),net=require('node:net'),dns=require('node:dns').promises,fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const run=path.resolve(process.argv[2]||''),port=Number(process.argv[3]);
assert.match(path.basename(run),/^stage1-\d{8}-\d{6}$/);assert.equal(JSON.parse(fs.readFileSync(path.join(run,'environment.json'))).run,path.basename(run));
assert.equal(port,33637);
const allowed=new Set(['mirrors.tuna.tsinghua.edu.cn','nodejs.org','registry.npmjs.org','cdn.playwright.dev','playwright.download.prss.microsoft.com']);
function publicV4(ip){const [a,b]=ip.split('.').map(Number);return net.isIPv4(ip)&&a>0&&a<224&&a!==10&&a!==127&&!(a===169&&b===254)&&!(a===172&&b>=16&&b<=31)&&!(a===192&&b===168)&&!(a===100&&b>=64&&b<=127)&&!(a===198&&[18,19].includes(b));}
const server=http.createServer((_,reply)=>{reply.writeHead(405);reply.end('CONNECT only');});
server.on('connect',async(req,client,head)=>{
  const match=/^([a-z0-9.-]+):443$/i.exec(req.url||'');
  if(!match||!allowed.has(match[1].toLowerCase())){client.end('HTTP/1.1 403 Forbidden\r\n\r\n');return;}
  const host=match[1].toLowerCase();let upstream;
  client.on('error',()=>upstream?.destroy());client.setTimeout(120000,()=>client.destroy());
  try{
    const {address}=await dns.lookup(host,{family:4});assert.ok(publicV4(address),'Only public vendor addresses permitted');
    upstream=net.connect({host:address,port:443});upstream.setTimeout(120000,()=>upstream.destroy());
    upstream.on('error',()=>client.destroy());client.on('close',()=>upstream.destroy());
    upstream.once('connect',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream);upstream.pipe(client);console.log('CONNECT '+host+':443');});
  }catch{client.end('HTTP/1.1 502 Bad Gateway\r\n\r\n');upstream?.destroy();}
});
server.listen(port,'127.0.0.1',()=>console.log('Owned build-only vendor tunnel '+path.basename(run)+' 127.0.0.1:'+port));
process.on('SIGTERM',()=>server.close());
