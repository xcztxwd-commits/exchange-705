#!/bin/sh
set -eu
test "${STAGE1_LINUX_FIXTURE:-}" = 1
test -n "${STAGE1_RUN:-}"
test -f "$STAGE1_RUN/private/ca.crt"
test -f "$STAGE1_LINUX_OWNER_PROOF"
node - <<'NODE'
const fs=require('fs'),path=require('path'),crypto=require('crypto'),assert=require('assert/strict');
const run=process.env.STAGE1_RUN,proof=JSON.parse(fs.readFileSync(process.env.STAGE1_LINUX_OWNER_PROOF));
const ca=fs.readFileSync(path.join(run,'private/ca.crt')),cert=new crypto.X509Certificate(ca);
assert.equal(path.basename(run),proof.run);assert.equal(cert.subject,'CN='+proof.run+' local fixture CA');
assert.equal(crypto.createHash('sha256').update(ca).digest('hex').toUpperCase(),proof.caFileSha256);
assert.equal(process.env.STAGE1_REDIS_PORT,String(proof.redisPort));assert.equal(process.env.STAGE1_HOST_IP,proof.hostIp);
assert.equal(process.env.STAGE1_REDIS_CONTAINER_ID,proof.redisContainerId);
assert.ok(proof.redisContainerId.match(/^[a-f0-9]{64}$/));assert.equal(proof.redisRunLabel,proof.run);
console.log('Owned Linux fixture identity and CA verified; normal TLS validation enabled');
NODE
cp "$STAGE1_RUN/private/ca.crt" /usr/local/share/ca-certificates/stage1-owned-fixture.crt
update-ca-certificates >/dev/null
for db in /home/pwuser/.pki/nssdb /home/pwuser/.local/share/pki/nssdb; do
  mkdir -p "$db"
  certutil -N -d "sql:$db" --empty-password
  certutil -A -d "sql:$db" -n "$(basename "$STAGE1_RUN")" -t 'C,,' -i "$STAGE1_RUN/private/ca.crt"
done
chown -R pwuser:pwuser /home/pwuser
export HOME=/home/pwuser
export NODE_EXTRA_CA_CERTS="$STAGE1_RUN/private/ca.crt"
export PLAYWRIGHT_PATH=/opt/browser/node_modules/playwright
export CHROME_PATH=/opt/browser/browsers/chromium_headless_shell-1234/chrome-headless-shell-linux64/chrome-headless-shell
exec runuser -u pwuser -- node "$@"
