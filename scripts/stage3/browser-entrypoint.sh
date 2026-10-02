#!/bin/sh
set -eu
HOME_DIR=/workspace/705/reports/stage3-20261002-232106
test "$(sha256sum "$HOME_DIR/private/ca.crt" | cut -d' ' -f1)" = "$STAGE3_CA_SHA256"
cp "$HOME_DIR/private/ca.crt" /usr/local/share/ca-certificates/stage3-owned.crt
update-ca-certificates >/dev/null
for db in /home/node/.pki/nssdb /home/node/.local/share/pki/nssdb; do
 mkdir -p "$db"
 certutil -N -d "sql:$db" --empty-password
 certutil -A -d "sql:$db" -n stage3-owned -t 'C,,' -i "$HOME_DIR/private/ca.crt"
done
chown -R node:node /home/node
export HOME=/home/node NODE_EXTRA_CA_CERTS="$HOME_DIR/private/ca.crt" PLAYWRIGHT_PATH=/app/node_modules/playwright
exec runuser -u node -- node /workspace/705/scripts/stage3/browser.cjs "$@"
