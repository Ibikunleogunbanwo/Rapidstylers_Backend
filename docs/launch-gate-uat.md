# RapidStylers — Launch-Gate UAT Checklist

Date: September 13, 2026 · Backend `dev` @ `50aa2f6` + simplification work · Frontend `main` @ `88c485c`

Purpose: gate the MVP launch against the nine acceptance-criteria areas from the
RapidStylers MVP Requirements doc. The automated columns below are **evidence of
what the test suite already proves**; the **Status** column is for the human UAT
pass — mark `PASS`, `FAIL (issue #)`, or `N/A`.

## Before you start

| Precondition | How to check |
|---|---|
| Backend healthy | `curl -i https://api.rapidstylers.ca/actuator/health` → 200 `UP` |
| Redis really connected (silent-degradation guard) | `docker logs rapidstylers-api 2>&1 \| grep -i "Redis connected"` → `probe=OK (PONG)` |
| Bootstrap admin seeded | non-empty `admin_accounts`; boot log has no `Refusing to seed bootstrap admin` |
| Frontend env configured (Vercel) | `REACT_APP_API_KEY` matches backend `APP_API_KEY`; `REACT_APP_API_BASE_URL` ends `/rapid_stylers`; `REACT_APP_GOOGLE_CLIENT_ID` set |
| Backend Google OAuth | `GOOGLE_CLIENT_ID` set on the VPS `.env` (Sign-in-with-Google is gated on it) |
| Payments mode intended | `STRIPE_MODE` = `test` or `live` matches the key set you expect |
| Full suite green | backend `./mvn21 test`; frontend `CI=true npm test -- --watchAll=false` + `CI=true npm run build` |

---

## 1. Authentication

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 1.1 | Customer signup → OTP email → verify → sign in | `/create_user_account`, `/generate_sign_up_otp_code`, `/verify_otp_code`, `/sign_in` | `RegistrationToBookingJourneyTest`, `OtpSecurityTest` | |
| 1.2 | Stylist signup path is separate from customer | `/create_styler`, `/styler_generate_otp`, `/styler_verify_otp`, `/styler_sign_in` | `RegistrationConsentValidationTest` | |
| 1.3 | Access token expires (~15 min) and refresh rotates silently | `/auth/refresh`, `src/hooks/remote/apiClient.js` (401 queue + replay) | `UserSignInRefreshTokenTest`, `RefreshTokenServiceTest` | |
| 1.4 | Replayed/stolen refresh token burns the whole token family | `RefreshTokenService` | `RefreshTokenServiceTest` | |
| 1.5 | Idle session signs the user out by role (cust 60 / styler 30 / admin 30 min) | `SessionActivityService`, `src/components/IdleTimeout.js` | `SessionActivityServiceTest` | |
| 1.6 | Admin sign-in is separate and capped (8 h absolute) | `/admin_sign_in` | `AdminSignInTest`, `AdminAccountInitializerTest` | |
| 1.7 | Google Sign-In creates/links an account from the ID token | `/google_sign_in` | `GoogleSignInTest`, `WebhookSignatureTest` (token verification) | |
| 1.8 | Password reset via OTP works and cannot enumerate accounts | reset endpoints | `PasswordResetJourneyTest`, `PasswordResetOtpVerificationTest` | |

## 2. Discovery

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 2.1 | Category browse returns services | `/list_service`, `/search_by_service` | `SearchReadCacheTest`, `CacheWarmerTest` | |
| 2.2 | Radius search returns approved stylists near a point | `/search_nearby` (Redis GEO index, MySQL fallback) | `LocationCacheServiceTest`, `SearchReadCacheTest` | |
| 2.3 | Filters (date, time, duration, openNow) and pagination work | `/search_nearby` body | `SearchReadCacheTest` | |
| 2.4 | Stylist profile shows services, portfolio, reviews | `/single_styler`, `/list_sub_service`, `/styler_own_portfolio` | `ProfileAssemblyTest` | |
| 2.5 | Address autocomplete/distance works and the Google key stays server-side | `/place_autocomplete`, `/place_details`, `/reverse-geocode`, `/detect-location` | `GeocodingService` paths via booking tests | |
| 2.6 | Public site still renders if the backend is unreachable | frontend fallback content (blog, landing) | frontend build + fallback tests | |

## 3. Booking

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 3.1 | Estimate → book → appointment appears for the client | `/booking_estimate`, `/book_appointment`, `/user_appointments` | `RegistrationToBookingJourneyTest`, `BookingWorkflowTest` | |
| 3.2 | **Exactly one customer wins a contested slot** (no double-booking) | unique index `uk_booking_slot_styler_date_start` (Flyway V13) + pessimistic lock | `ConcurrentTwoCustomerBookingTest` (two live customers), `ConcurrentBookingTest` | |
| 3.3 | Stylist accepts / declines with a note; client sees the decision | `/accept_appointment`, `/decline_appointment`, `/styler_appointments` | `BookingWorkflowTest` (decision-note sanitizing) | |
| 3.4 | Complete requires the appointment start to have passed | `/complete_appointment` | `BookingWorkflowTest` | |
| 3.5 | Client cancel and stylist cancel release held slots | `/cancel_appointment`, `/styler_cancel_appointment` | `BookingWorkflowTest`, `RefundServiceTest` | |
| 3.6 | Invalid transitions are refused (no skipping states) | `transitionAppointment` guards | `BookingHardeningTest`, `BookingWorkflowTest` | |
| 3.7 | A failed payment can be retried by the client | `/retry_appointment_payment` | `StripeLifecycleTest`, `BookingWorkflowTest` | |

## 4. Pricing (backend-owned)

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 4.1 | Price shown equals price charged for the same inputs | `/booking_estimate` vs `/book_appointment` | `TravelSettingsTest`, `BookingWorkflowTest` | |
| 4.2 | First 15 km included; travel fee applies only above the included radius | `calculateTravelPricing` | `TravelSettingsTest`, `BookingWorkflowTest` | |
| 4.3 | Home-service bookings require a travel distance | `calculateTravelPricing` validation | `BookingWorkflowTest` (`homeServiceBookingStoresTravelFeeOnlyForDistanceAboveIncludedRadius`) | |
| 4.4 | Commission is snapped at booking time; later rate changes never rewrite it | `commissionPercent` snapshot | `CommissionSnapshotTest`, `PlatformSettingsTest` | |
| 4.5 | Client-supplied prices are ignored | request DTOs carry no price | `apiService.test.js` ("without trusting client price") | |
| 4.6 | Payout breakdown = total − commission, matching the snapshot | `/styler/payouts` | `PayoutSummaryTest`, `DTOServiceRefundTest` | |

## 5. Stylist operations

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 5.1 | Onboarding completes and admin approval flips verification to APPROVED | `/create_styler`, `/admin/update_styler_verification` | `AdminConnectStatusTest`, `RegistrationToBookingJourneyTest` | |
| 5.2 | Services and durations are managed (15-min granularity, 15–480 min) | `/create_sub_service`, `/update_sub_service` | `AvailabilityValidationTest`, `SubServiceSanitizeTest` | |
| 5.3 | Availability and exceptions save and drive slot generation | `/update_styler_availability`, `/add_availability_exception` | `AvailabilityValidationTest`, `AvailabilityUpdateDataValidationTest` | |
| 5.4 | Portfolio upload signs server-side and respects folder prefixes | `/get_upload_signature` | `CloudinaryControllerTest` | |
| 5.5 | Travel settings (included km, base fee) persist and affect pricing | `/styler/travel_settings`, `/update_styler_travel_settings` | `TravelSettingsTest` | |
| 5.6 | Stripe Connect onboarding → COMPLETE enables payouts; REJECTED shows the reason | `/styler/connect_account`, `/styler/connect_status` | `ConnectWebhookTest` | |
| 5.7 | Payout page shows earnings, commission, and live balances | `/styler/payouts` | `PayoutSummaryTest`, `PayoutReversalServiceTest` | |

## 6. Admin operations

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 6.1 | Verification queue reviews stylist IDs (approve/reject/suspend) | `/admin/styler_verification_queue` | `AdminConnectStatusTest`, `SecurityConfigTest` | |
| 6.2 | Review moderation queue works | `/admin/review_moderation_queue` | `ReviewSanitizeTest`, `SecurityConfigTest` | |
| 6.3 | Categories/services and blog content are manageable | `/create_service`, `/create_blog` … | `BlogPostSanitizeTest`, `SecurityConfigTest` | |
| 6.4 | Support tickets are viewable and updatable | `/admin/support_tickets`, `/admin/update_support_ticket` | `SupportTicketSanitizeTest`, `TicketResponseSanitizeTest` | |
| 6.5 | **Refunds require step-up re-auth and are idempotent** | `/admin/refund` + `X-Step-Up-Password` | `AdminRefundStepUpTest`, `RefundServiceTest` | |
| 6.6 | Commission can be changed at runtime and takes effect without restart | `/admin/settings/commission` | `PlatformSettingsTest` | |
| 6.7 | Payment reconciliation cross-checks Stripe against bookings | `/admin/payment_reconciliation` | `PaymentReconciliationServiceTest` | |
| 6.8 | KPIs and audit logs are readable | `/admin/kpis`, `/admin/audit_logs` | `AdminAccountControllerTest`, `ThrottledLogMetricsTest` | |
| 6.9 | Failed outbox events can be retried from the console | `/admin/outbox/…` | `OutboxEventServiceTest`, `OutboxPublisherTest` | |

## 7. Notifications

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 7.1 | Booking/decision emails arrive without blocking the booking write | outbox → Kafka → consumer (Resend) | `OutboxPublisherTest`, `NotificationEventConsumerTest` | |
| 7.2 | A vendor outage never fails the booking transaction | transactional outbox | `OutboxEventServiceTest`, `BookingWorkflowTest` (queues instead of sending) | |
| 7.3 | Duplicate sends are suppressed | `NotificationDedupService` | `NotificationDedupServiceTest` | |
| 7.4 | Exhausted retries land on the DLQ instead of looping | retry + DLQ topics | `NotificationEventConsumerTest` | |
| 7.5 | In-app notifications + preferences work | `/notifications`, `/notification_preferences` | `NotificationTest` | |
| 7.6 | Payment receipts go out on capture | `sendPaymentReceipt` | `StripeLifecycleTest`, `PaymentOpsService` receipt path | |

## 8. Security

| # | Check | Implementation | Automated evidence | Status |
|---|---|---|---|---|
| 8.1 | Requests without `x-api-key` are rejected (except health/files/stripe webhook) | `AppConfig` gate | `SecurityConfigTest` | |
| 8.2 | Role checks hold for CUSTOMER / STYLER / ADMIN paths | `JwtAuthFilter` + `@PreAuthorize` | `JwtAuthFilterTest`, `SecurityConfigTest` | |
| 8.3 | CORS accepts only the allow-listed origins; preflight works | `CORS_ALLOWED_ORIGINS` | `SecurityHeadersTest` | |
| 8.4 | Rate limits lock out repeated login/OTP/upload-signature abuse | Redis Lua counters | `RateLimiterServiceTest`, `RateLimiterServiceRedisIntegrationTest` | |
| 8.5 | Stripe webhooks are signature-verified | `/stripe/webhook` | `WebhookSignatureTest` | |
| 8.6 | Upload signatures are folder-restricted | Cloudinary signing | `CloudinaryControllerTest` | |
| 8.7 | `/decrypt` is ADMIN-only and the AES fallback key cannot boot outside tests | `EncryptionConfig` guard | `DecryptEndpointSecurityTest`, `EncryptionConfigTest` | |
| 8.8 | Security headers present (nosniff, frame DENY, HSTS, CSP) | header filter | `SecurityHeadersTest` | |
| 8.9 | No secrets in git history | gitleaks in CI | CI `gitleaks` job | |

## 9. Out-of-MVP exclusions (must stay absent)

| # | Excluded capability | Verified state | Status |
|---|---|---|---|
| 9.1 | Cash incentives | No customer wallet / cash-out surface exists | |
| 9.2 | Advertising income | `adSlot.js` renders nothing and loads no scripts until `REACT_APP_ADSENSE_CLIENT` is set — **off** | |
| 9.3 | Loyalty points | ⚠️ **PRESENT AND USER-VISIBLE** — see gap G1 | |
| 9.4 | Referral rewards | ⚠️ **PRESENT AND USER-VISIBLE** — see gap G1 | |
| 9.5 | Virtual consultations | No consultation/video feature in either repo | |
| 9.6 | Analytics monetization | Admin KPIs are internal ops metrics only; nothing is sold or shared | |

---

## Gaps found in this pass

### G1 — Loyalty points and referral rewards are live despite being out-of-MVP *(blocking decision)*

The MVP Requirements doc lists **loyalty points** and **referral rewards** among
the explicit out-of-MVP exclusions ("deferred until booking, trust, payment
readiness, and operations are stable"), but both are implemented and reachable
by clients today:

- Backend: `GET /loyalty_account`, `POST /apply_referral`, `loyalty_accounts` +
  `referrals` tables, and completion points awarded on every completed booking
  (`AWARD_LOYALTY` audit action in `AppService`).
- Frontend: `/loyalty` route (`src/pages/users/pages/loyalty.js`), linked from
  account settings, plus `APIService.getLoyaltyAccount` / `applyReferral`.

Either the exclusion list needs stakeholder sign-off to move loyalty/referrals
**into** the MVP (and then this row flips to PASS with acceptance criteria
written), or the surface must be hidden behind a flag before launch. Decide
before the gate closes.

### G2 — Frontend suite has one local failure from a temporary edit *(not a code defect)*

`src/components/IdleTimeout.test.js` fails against the working tree because
`src/components/IdleTimeout.js` currently holds **temporary 60-second test
windows** ("revert to real windows after the proof"). Committed code is green;
revert those values (or re-baseline the test) before the UAT pass so a red suite
can't mask a real regression.

### G3 — Deploy-time env confirmation *(operational)*

Sign-in-with-Google and ads are both env-gated, so a silent misconfiguration
looks like a missing feature rather than an error:
- `REACT_APP_GOOGLE_CLIENT_ID` (Vercel) + `GOOGLE_CLIENT_ID` (VPS `.env`) must
  both be set for the Google button to render.
- `REACT_APP_ADSENSE_CLIENT` must stay empty until AdSense approval (intended).

---

## Sign-off

| Area | Tester | Date | Result |
|---|---|---|---|
| 1 Authentication | | | |
| 2 Discovery | | | |
| 3 Booking | | | |
| 4 Pricing | | | |
| 5 Stylist operations | | | |
| 6 Admin operations | | | |
| 7 Notifications | | | |
| 8 Security | | | |
| 9 Exclusions (G1 resolved) | | | |
