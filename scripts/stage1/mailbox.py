"""Controlled real TLS SMTP receiver. Authenticates test credentials, stores MIME privately.
No application response is intercepted and no verification code is forged.
"""
import base64, json, socketserver, ssl, threading, uuid, sys
from contextlib import nullcontext
from pathlib import Path

home = Path(sys.argv[1]).resolve(); private = home/'private'
identity = json.loads((private/'identities.json').read_text())
messages = private/'mail'; messages.mkdir(exist_ok=True)
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.minimum_version = ssl.TLSVersion.TLSv1_2
context.load_cert_chain(private/'server.crt', private/'server.key')

class Handler(socketserver.StreamRequestHandler):
    def setup(self):
        # Handshake belongs to this worker, never the single accept thread.
        self.request.settimeout(5)
        self.request=context.wrap_socket(self.request,server_side=True)
        super().setup()
    def finish(self):
        try: super().finish()
        finally: self.request.close()
    def reply(self, text): self.wfile.write((text+'\r\n').encode()); self.wfile.flush()
    def handle(self):
        self.request.settimeout(20); self.reply('220 local stage1 SMTP ready')
        authenticated=False; recipients=[]
        while True:
            raw=self.rfile.readline(8192)
            if not raw: return
            line=raw.decode('ascii',errors='replace').strip(); command=line.split(' ',1)[0].upper()
            if command in ('EHLO','HELO'):
                self.reply('250-localhost'); self.reply('250 AUTH LOGIN PLAIN')
            elif line.upper().startswith('AUTH LOGIN'):
                parts=line.split(' ',2)
                if len(parts)==3: username=parts[2]
                else: self.reply('334 VXNlcm5hbWU6'); username=self.rfile.readline().strip()
                try:
                    user=base64.b64decode(username,validate=True).decode()
                    self.reply('334 UGFzc3dvcmQ6'); password=base64.b64decode(self.rfile.readline().strip(),validate=True).decode()
                    authenticated=user=='stage1' and password==identity['smtpPassword']
                except (ValueError,UnicodeError): authenticated=False
                self.reply('235 authenticated' if authenticated else '535 invalid fixture credentials')
            elif line.upper().startswith('AUTH PLAIN'):
                value=line.split(' ',2)[2] if len(line.split(' ',2))==3 else ''
                if not value: self.reply('334 '); value=self.rfile.readline().strip()
                _,user,password=base64.b64decode(value).decode().split('\x00')
                authenticated=user=='stage1' and password==identity['smtpPassword']
                self.reply('235 authenticated' if authenticated else '535 invalid fixture credentials')
            elif command in ('MAIL','RCPT'):
                if not authenticated: self.reply('530 auth required'); continue
                if command=='MAIL': recipients=[]
                else: recipients.append(line[8:].strip('<>'))
                self.reply('250 accepted')
            elif command=='DATA':
                if not authenticated: self.reply('530 auth required'); continue
                self.reply('354 send message'); data=bytearray()
                while True:
                    row=self.rfile.readline(65536)
                    if row==b'.\r\n': break
                    if not row or len(data)+len(row)>1024*1024: return
                    data.extend(row[1:] if row.startswith(b'..') else row)
                identifier=uuid.uuid4().hex
                (messages/(identifier+'.eml')).write_bytes(data)
                (messages/(identifier+'.json')).write_text(json.dumps({'recipients':recipients}),encoding='utf-8')
                self.reply('250 stored in private local mailbox')
            elif command=='QUIT': self.reply('221 goodbye'); return
            elif command in ('RSET','NOOP'): self.reply('250 ok')
            else: self.reply('502 not supported')

class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address=False; daemon_threads=True

if '--verify-auth' in sys.argv:
    # Exercise the listening owned TLS receiver; never print credentials or send mail.
    import socket
    client=ssl.create_default_context(cafile=str(private/'ca.crt'))
    for mode in ('LOGIN','LOGIN_INITIAL','PLAIN','REJECT','CONCURRENT_HANDSHAKE'):
        # The last case holds an accepted raw peer without ClientHello.
        with (socket.create_connection(('127.0.0.1',465),5) if mode=='CONCURRENT_HANDSHAKE' else nullcontext()):
            with client.wrap_socket(socket.create_connection(('127.0.0.1',465),5),server_hostname='smtp.localhost') as connection:
                stream=connection.makefile('rwb')
                def send(value): stream.write(value+b'\r\n'); stream.flush()
                def receive(): return stream.readline().strip()
                assert receive().startswith(b'220');send(b'EHLO smtp.localhost')
                assert receive().startswith(b'250-') and receive().startswith(b'250 ')
                user=base64.b64encode(b'stage1');password=identity['smtpPassword'] if mode!='REJECT' else identity['smtpPassword']+'-invalid'
                if mode in ('PLAIN','REJECT','CONCURRENT_HANDSHAKE'):
                    send(b'AUTH PLAIN '+base64.b64encode(('\0stage1\0'+password).encode()))
                else:
                    send(b'AUTH LOGIN'+(b' '+user if mode=='LOGIN_INITIAL' else b''))
                    if mode=='LOGIN': assert receive()==b'334 VXNlcm5hbWU6';send(user)
                    assert receive()==b'334 UGFzc3dvcmQ6';send(base64.b64encode(password.encode()))
                assert receive().startswith(b'535' if mode=='REJECT' else b'235'), mode+' authentication contract failed'
                send(b'QUIT');assert receive().startswith(b'221')
                print('PASS TLS CA + hostname + SMTP '+mode,flush=True)
    sys.exit(0)

with Server(('127.0.0.1',465),Handler) as server:
    print('Stage1 authenticated TLS SMTP on loopback:465',flush=True); server.serve_forever()
