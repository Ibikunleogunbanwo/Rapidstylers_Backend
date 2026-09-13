# DNS records (Cloudflare)

Production shape (see `docs/architecture.md` §8):

```text
rapidstylers.ca / www   -> Vercel frontend
api.YOURDOMAIN.com       -> VPS (this backend)
```

## Records

| Type | Name | Value | Proxy | Purpose |
|---|---|---|---|---|
| A | `api` | `<VPS IPv4>` | Proxied (orange) | Backend API on the VPS |
| AAAA | `api` | `<VPS IPv6>` (only if the VPS has IPv6) | Proxied | Backend over IPv6 |
| A | `@` (apex) | `76.76.21.21` | Proxied | Frontend on Vercel (Vercel's anycast IP — **confirm in the Vercel dashboard**, it changes) |
| CNAME | `www` | `cname.vercel-dns.com` | Proxied | Frontend www → Vercel |

> Frontend values above are Vercel's published defaults at the time of writing.
> Vercel's dashboard → Domains shows the exact records to create for your
> project — follow it if it differs.

## Rules that keep the shape healthy

1. **Only HTTP(S) records are public.** No A/AAAA for Redis, Kafka, Kafka UI, or
   MySQL; they stay on the private Docker network / loopback. SSH (22) has no
   DNS record either.
2. **`api` must be Proxied (orange cloud)** — that is what gives TLS at the
   edge, WAF, and trusted `CF-Connecting-IP` headers. Grey-clouded, the origin
   cert stops validating (see `origin-cert.md`).
3. DNS propagation check after adding records:

   ```bash
   dig +short api.YOURDOMAIN.com          # Cloudflare edge IPs (104.16.x / 172.64.x ...)
   dig +short YOURDOMAIN.com              # 76.76.21.21 (Vercel)
   ```

4. Everything else that must be reachable from outside goes through the API:
   Stripe webhooks hit `https://api.YOURDOMAIN.com/rapid_stylers/stripe/webhook`
   (set that URL in the Stripe dashboard), not the VPS IP.

## Optional extras

- `google-site-verification` TXT if Google ever needs to verify the domain.
- `_dmarc`, `SPF`, `MX` only if you add custom-domain email later (Resend
  currently sends from its own verified sender).
