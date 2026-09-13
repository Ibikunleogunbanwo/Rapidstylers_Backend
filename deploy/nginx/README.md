# nginx for the RapidStylers API

Templates, not live config: copy to the VPS, substitute your domain, verify, reload.

**Role in the system design** (see `docs/architecture.md` §8): nginx is the only
host-level process in front of the API. It terminates TLS for `api.<domain>`
and proxies to the loopback-published container port — Cloudflare talks to it,
never directly to Docker. The real-IP snippet is what keeps per-user rate
limiting accurate through that chain.

## Files

| File | Install to | Purpose |
|---|---|---|
| `sites-available/api.YOURDOMAIN.com.conf` | `/etc/nginx/sites-available/api.<your-domain>.conf` | TLS site: `api.<your-domain>` → `127.0.0.1:9095` |
| `cloudflare-real-ip.conf` | `/etc/nginx/conf.d/` | Trust Cloudflare ranges; `CF-Connecting-IP` becomes the real client IP |

## Install

```bash
DOMAIN=api.example.com   # your real API subdomain

sudo cp deploy/nginx/sites-available/api.YOURDOMAIN.com.conf \
     /etc/nginx/sites-available/$DOMAIN.conf
sudo sed -i "s/api.YOURDOMAIN.com/$DOMAIN/g" /etc/nginx/sites-available/$DOMAIN.conf

sudo cp deploy/nginx/cloudflare-real-ip.conf /etc/nginx/conf.d/

sudo ln -s /etc/nginx/sites-available/$DOMAIN.conf /etc/nginx/sites-enabled/

# Certificates MUST exist before reload (see ../cloudflare/origin-cert.md)
sudo nginx -t && sudo systemctl reload nginx
```

## Prerequisites on the VPS

1. DNS: `api.<your-domain>` → VPS IP, proxied (see `../cloudflare/DNS-records.md`).
2. Cloudflare Origin CA cert + key at `/etc/ssl/cloudflare-origin.{pem,key}`.
3. Cloudflare SSL/TLS mode **Full (strict)**.
4. Ports 443 (+ 80 for the redirect) open in ufw — `bootstrap-vps.sh` does this.

## Verify

```bash
curl -i https://api.example.com/actuator/health        # 200 {"status":"UP"}
curl -i -H "x-api-key: $APP_API_KEY" \
     https://api.example.com/rapid_stylers/list_service  # 200
# Real client IP reaches the app:
grep -i "client" /var/log/nginx/access.log | tail -1   # should NOT be 104.16.x / 172.x
```

## Notes

- `api.example.com/actuator/metrics` is reachable through here too (key-gated by the app).
- Stripe webhooks arrive on this same site — no extra routing needed.
- The template assumes Cloudflare terminates TLS at the edge **and** you run a
  Cloudflare Origin CA cert on nginx (Full strict). If you instead terminate
  TLS only at Cloudflare with a grey-clouded origin, drop the `ssl_*` lines and
  serve plain HTTP on 80 — but then `CF-Connecting-IP`/real-IP trust still
  needs Cloudflare proxying on the record.
