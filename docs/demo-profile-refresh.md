# Demo profile identity refresh

The seed initializer now creates fictional personal names, distinct studio names, category-specific biographies and five distinct sample profile photos per category. Bios identify the profiles as fictional. Existing catalogues, prices, availability, addresses, ratings and booking history retain their existing rules and data.

On an enabled seed run, existing approved profiles are refreshed only when all checks match: DEMO-prefixed stylist ID, the seed-only email domain, first name `Demo`, and a legacy `Demo ` business name. Already refreshed or customized profiles and QA registrations are left alone. No account is deleted; IDs, email, passwords and payment configuration remain unchanged.

## Schedules, services, addresses and reviews

The same demo-only refresh also does the following, for DEMO-prefixed rows only:

- **Hours:** every seeded stylist is open Monday to Sunday. Each stylist gets one of five broad schedules (8 to 12 hour days, shorter on weekends). Only empty days are filled, and the two short windows the old seeder wrote (Tue 10:00-17:00, Sat 09:00-16:00) are widened. Any other shift, including QA shifts, is kept. Bookings are never touched.
- **Makeup:** makeup artists get a makeup menu (day, soft glam, bridal, event, lesson). Hair services an older seed gave them are renamed in place, so the same service row and id stay behind every existing booking and review.
- **Addresses:** each seeded profile in a category gets its own street address and matching map point, spread across downtown, NW, NE, SW and SE Calgary, with the `America/Edmonton` time zone. Only blank addresses or addresses the old seeder handed out are moved; an edited address stays.
- **Reviews:** new review text is specific to the category, uses the professional's first name instead of "she", and never mentions a neighbourhood. Seeded reviews that still carry the old text are reworded; score, date, reviewer and booking stay the same. Real reviews are never changed.

## Apply after deploying the backend image

In `/opt/rapidstylers/backend/.env`, temporarily set `DEMO_SEED=true`. Keep `APP_DEMO_SEED=false` so the live service does not seed. Do not reset the database.

```bash
cd /opt/rapidstylers/backend
sudo docker compose -f docker-compose.prod.yml --profile demo up -d --no-deps app-demo
sudo docker compose -f docker-compose.prod.yml logs --tail 100 app-demo
```

Once startup succeeds, restore `DEMO_SEED=false` in `.env` and recreate only `app-demo` with the same command. Verify names in demo search after the short search-cache TTL expires. The identity refresh is repeatable: renamed profiles no longer match the legacy condition.

The code and images must be deployed before these changes appear on the public demo. This document does not indicate that deployment has occurred.

## Profile photos

New profiles receive stable relative `/images/` URLs. Existing seed-owned DEMO profiles receive a photo when their profile image is empty or matches an explicitly superseded seed sample URL. Explicit uploaded photos are preserved. The image selection follows the owner’s Black-only casting direction for the demo/marketing samples. Hair uses five curated samples, nails use four generated samples and one curated sample, lashes use five generated samples, and barber/makeup each use three curated and two generated samples. Deploy the frontend assets before running the demo-only backend refresh, otherwise new image URLs may return 404 until the frontend is updated. The gallery images and generated examples do not represent work uploaded by these fictional professionals.
