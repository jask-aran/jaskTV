#!/usr/bin/env bash
# Puts jasktv-core on a Linux server as a *helper* (matching, lists, guide, posters; no torrents or streaming) for when
# the home core is off, reachable on the server's Tailscale address and, through a Cloudflare Tunnel, at an https
# address from anywhere.
#
#   ./deploy-helper.sh <ssh host> <hostname for the tunnel>
#   ./deploy-helper.sh jask-vps jasktv-core.jask-aran.com
#
# Asks for your jaskTV email and password once (core keeps its own session, never the password), and the first time
# opens a Cloudflare sign-in link for the tunnel. Run ./build.sh first. Undo on the server:
#   systemctl disable --now jasktv-core cloudflared-jasktv; rm -r /root/jasktv-core
#   rm /etc/systemd/system/{jasktv-core,cloudflared-jasktv}.service; cloudflared tunnel delete jasktv-core
set -euo pipefail
cd "$(dirname "$0")"
host="${1:?ssh host}"; name="${2:?hostname for the tunnel, e.g. jasktv-core.example.com}"
server=$(grep '^jasktv.accountServer=' ../local.properties | cut -d= -f2-)
dir=/root/jasktv-core

ts=$(ssh "$host" 'tailscale ip -4 | head -1')
[ -n "$ts" ] || { echo "No Tailscale address on $host"; exit 1; }
echo "== core on $host, listening on its Tailscale address $ts (not the public internet)"
ssh "$host" "mkdir -p $dir && chmod 700 $dir"
scp -q dist/jasktv-core-linux-x86_64 "$host:$dir/jasktv-core"
printf '{\n  "listen": "%s:7000",\n  "role": "helper",\n  "account_server": "%s",\n  "public_url": "https://%s"\n}\n' "$ts" "$server" "$name" \
  | ssh "$host" "cat > $dir/jasktv-core.json && chmod +x $dir/jasktv-core"

echo "== sign core in to your jaskTV account"
if ssh "$host" "grep -q '\"token\"' $dir/jasktv-core-state.json 2>/dev/null"; then echo "already signed in"
else
  read -rp "Email: " email; read -rsp "Password: " password; echo
  printf '%s\n%s\n' "$email" "$password" | ssh "$host" "$dir/jasktv-core login"
  unset password
fi
ssh "$host" "cd $dir && ./jasktv-core install-service"

echo "== Cloudflare Tunnel: https://$name → core"
ssh -t "$host" "test -f ~/.cloudflared/cert.pem || cloudflared tunnel login"
ssh "$host" bash -s -- "$name" "$ts" <<'REMOTE'
set -euo pipefail
name="$1"; ts="$2"
cloudflared tunnel info jasktv-core >/dev/null 2>&1 || cloudflared tunnel create jasktv-core
id=$(cloudflared tunnel list -o json | python3 -c "import sys,json; print([t['id'] for t in json.load(sys.stdin) if t['name']=='jasktv-core'][0])")
cloudflared tunnel route dns jasktv-core "$name" || true
mkdir -p /etc/cloudflared
cat > /etc/cloudflared/jasktv-core.yml <<EOF
# jasktv-core helper: only this hostname, to core on the Tailscale address; everything else 404.
tunnel: $id
credentials-file: /root/.cloudflared/$id.json
ingress:
  - hostname: $name
    service: http://$ts:7000
  - service: http_status:404
EOF
cat > /etc/systemd/system/cloudflared-jasktv.service <<EOF
[Unit]
Description=Cloudflare Tunnel for jasktv-core
After=network-online.target jasktv-core.service
Wants=network-online.target

[Service]
ExecStart=/usr/local/bin/cloudflared --no-autoupdate --config /etc/cloudflared/jasktv-core.yml tunnel run
Restart=on-failure
RestartSec=10
Nice=10

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now cloudflared-jasktv
REMOTE

echo "== checking"
sleep 5
ssh "$host" "curl -s -m 5 http://$ts:7000/health; echo"
curl -s -m 15 "https://$name/health"; echo
