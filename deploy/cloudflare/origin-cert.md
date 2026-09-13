# Cloudflare Origin CA certificate

Terminating TLS on the VPS with a Cloudflare-signed origin certificate lets you
run **SSL/TLS mode: Full (strict)** — Cloudflare encrypts to the browser AND to
nginx, and nginx only trusts Cloudflare's CA. No public CA / Let's Encrypt
needed for the origin.

## Create (dashboard)

1. Cloudflare dashboard → your zone → **SSL/TLS → Origin Server → Create Certificate**.
2. Scope: `api.YOURDOMAIN.com` (single hostname is enough; you can include the
   apex if nginx on the same box will ever serve it).
3. Key type: **ECC (P-256)** · Validity: **15 years** (origin certs don't need
   the 90-day rotation of public CAs).
4. Copy both values — the **Origin Certificate** (PEM, includes the Cloudflare
   Origin CA root — keep it whole, do not split) and the **Private key**.

## Install on the VPS

```bash
# Place them as the nginx template expects:
sudo tee /etc/ssl/cloudflare-origin.pem  >/dev/null <<'EOF'   # paste PEM (cert bundle)
-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----
EOF

sudo tee /etc/ssl/cloudflare-origin.key >/dev/null <<'EOF'    # paste private key
-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----
EOF

sudo chmod 644 /etc/ssl/cloudflare-origin.pem
sudo chmod 600 /etc/ssl/cloudflare-origin.key

# Make sure the cert/key match, then load nginx:
sudo openssl x509 -noout -subject -in /etc/ssl/cloudflare-origin.pem
sudo nginx -t && sudo systemctl reload nginx
```

## Cloudflare settings that pair with it

| Setting | Value | Why |
|---|---|---|
| SSL/TLS mode | **Full (strict)** | Browser→CF and CF→origin both encrypted; strict validates the origin cert against Cloudflare's CA |
| Always Use HTTPS | On | Redirects http→https at the edge |
| HSTS | Optional | The API sends its own `Strict-Transport-Security` header; enabling at Cloudflare adds a second layer for the whole zone |
| Authenticated Origin Pulls | Optional | Origin only accepts connections presenting a Cloudflare client cert — defense in depth; requires an extra cert on nginx (`ssl_client_certificate` + `ssl_verify_client on`) |

## If the zone is DNS-only (grey cloud) for `api.`

Origin CA certificates are **only trusted when the record is proxied**. If you
test with the record grey-clouded, nginx TLS will fail client validation — use
a normal public cert in that case, or proxy the record first.
