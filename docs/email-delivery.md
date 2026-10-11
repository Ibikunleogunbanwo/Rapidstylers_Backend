# Email delivery and newsletter operations

RapidStylers sends through Resend. These changes do not send email or configure a provider account automatically.

## Published transactional templates

The mail queue renders the **published** copy at delivery time. Drafts have no effect. The renderer HTML-escapes placeholder values and removes subject line breaks. Unpublished, empty or invalid stored templates fall back to the existing built-in message; they do not break Kafka acknowledgement/retry handling.

| Content key | Actual trigger and recipient |
| --- | --- |
| `email:welcome_customer` | Newly created customer's existing `CUSTOMER_WELCOME` event |
| `email:welcome_stylist` | Successful new stylist registration's `STYLER_WELCOME` event, stored in the signup transaction |
| `email:booking_received` | `BOOKING_REQUESTED`, customer |
| `email:booking_confirmed` | `BOOKING_CONFIRMED`, customer |
| `email:booking_cancelled` | `BOOKING_CANCELLED`, customer |
| `email:booking_reminder` | **No trigger exists.** It can be saved/previewed/published, but no appointment reminder scheduler exists in this backend. Implementing that scheduler requires separate timing, eligibility and deduplication rules. Signup reminders are a different flow and never use this booking template. |

Booking notifications to the stylist retain their role-specific built-in wording; the customer templates must not turn a stylist's new-booking alert into a customer's confirmation. Payment receipts, refunds, OTPs and signup reminders keep their existing messages. Booking times in templates include the stylist's declared time zone.

## Resend bounce and complaint webhook

Configure a Resend webhook for `email.bounced` and `email.complained` pointing directly to `https://<live-api-host>/rapid_stylers/resend/webhook`. Set `RESEND_WEBHOOK_SECRET` to that endpoint's signing secret using the deployment secret manager. This is a webhook signing secret, not the Resend sending API key. Missing configuration returns 503; absent/invalid signatures or timestamps more than five minutes away return 401. Verification uses the raw body, `svix-id`, `svix-timestamp`, and `svix-signature`, HMAC-SHA256 and constant-time comparison, including rotated v1 signatures.

Only `data.bounce.type=Permanent` bounces and spam complaints create newsletter suppression records. Temporary bounces, delivery delays and unrelated events are ignored. Repeated delivery is idempotent by normalized address and the database unique email constraint; concurrent insertion races may receive an error once and succeed when Resend retries. Existing manual suppressions remain intact. Newsletter delivery checks suppressions immediately before sending. This does not unsubscribe the person or disable necessary transactional messages; Resend also maintains its own provider suppression rules.

Keep the webhook route free of browser login, shared API-key gates, redirects and Cloudflare challenges. Authentication is the verified signature. Do not put webhook secrets in source control or browser configuration. Select these events in the **live** Resend account, not the demo API; demo read-only mode intentionally rejects writes.

## Gmail/RFC 8058 one-click unsubscribe

Real subscriber campaign emails include `List-Unsubscribe: <https://<api-host>/rapid_stylers/newsletter_one_click_unsubscribe?token=<subscriber-token>>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click`. Transactional messages and admin preview/test sends do not receive subscriber headers.

Set `NEWSLETTER_API_URL` to the direct public HTTPS API base URL (default `https://api.rapidstylers.ca`) and `NEWSLETTER_SITE_URL` to the website origin (default `https://rapidstylers.ca`). Each campaign's normal footer points to `/newsletter/unsubscribe?token=...`, which displays preferences and asks the person to unsubscribe; GET requests never change subscription state. Mailbox providers can POST `List-Unsubscribe=One-Click` as URL-encoded or multipart form data to the token endpoint without cookies, sign-in or an API key. Tokens are the existing stable 256-bit random subscriber tokens; unsubscribe is idempotent and does not affect booking emails.

Before enabling live campaigns, verify the domain in Resend and publish the exact SPF/DKIM records the provider gives you; configure aligned DMARC for the sender domain. Check a received raw email to ensure the valid aligned DKIM signature covers **both** List-Unsubscribe headers, as RFC 8058 requires. The code can supply the headers but cannot guarantee provider DKIM signing or Gmail's UI decisions. Ensure the endpoint is publicly accessible over HTTPS without redirects/challenges. Do not modify Cloudflare or DNS from this implementation task.

## Verification

Focused tests use mocked delivery or a loopback HTTP inbox, never Resend. Run with a supported JDK via `./mvn21 -Dtest=EmailConfigTest,NotificationEventConsumerTest,OutboxEventServiceTest,ResendWebhookTest,OneClickUnsubscribeTest,NewsletterMailerHeadersTest,EmailPublicEndpointsTest,StylerAddressRequirementTest test`. The existing database-backed `NewsletterTest` also covers subscription and campaign behavior when a test database is configured.

References: [Resend signature verification](https://resend.com/docs/webhooks/verify-webhooks-requests), [Svix signature algorithm](https://docs.svix.com/receiving/verifying-payloads/how-manual), [Resend permanent bounce schema](https://resend.com/docs/webhooks/emails/bounced), [Resend complaint schema](https://resend.com/docs/webhooks/emails/complained), [Resend custom email headers](https://resend.com/docs/api-reference/emails/send-email), [RFC 8058](https://www.rfc-editor.org/rfc/rfc8058).
