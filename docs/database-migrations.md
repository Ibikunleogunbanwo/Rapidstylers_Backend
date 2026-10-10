# Database migrations: versions, checks and rollback

Flyway owns the schema. Production runs `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`
(set in `docker-compose.prod.yml`): the app checks the tables match its entities
at startup and refuses to start if they don't. It never changes them itself. Every
schema change is therefore a numbered file in `src/main/resources/db/migration`.

## How they were verified

- **Fresh database:** an empty MySQL 8 built from V1 to the latest version, then
  the full test suite runs against it (the tests run with `validate` too). Done
  for every version up to V22.
- **Copy of production:** on 2026-10-10 a dump of the live database was migrated
  to V16 and the app started against it under `validate`. V17 to V22 only add
  columns and indexes.

Both passed. Every migration from V16 on checks before it changes anything
(`information_schema` lookups), so running it on a database that already has the
change is a no-op.

## Recent versions

| Version | Change | Kind |
| --- | --- | --- |
| V16 | Adds four unique keys V13 missed and aligns column sizes with production | No-op on production |
| V17 | `appointments.service_address` | New nullable column |
| V18 | `appointments.idempotency_key`, `idempotency_fingerprint` and unique index `uk_appointments_idempotency_key` | New nullable columns, index |
| V19 | `otp_codes.failed_attempts` (default 0) | New column with default |
| V20 | `appointments.payout_due_at`, `service_confirmed_at`, `service_dispute_status`, `service_dispute_reason`, `service_disputed_at`, index `idx_appointments_payout_due_at` | New nullable columns, index |
| V21 | `appointments.payout_hold_reason` | New nullable column |
| V22 | `stylers.cover_image_url` | New nullable column |

To see what production has applied (the commands below assume the MySQL
container exposes `MYSQL_ROOT_PASSWORD` and `MYSQL_DATABASE`; use the names your
container has):

```sh
docker exec rapidstylers-mysql sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" \
  -e "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10"'
```

## Before every deploy that adds a migration

Take a dump first. It is the recovery point if anything goes wrong.

```sh
docker exec rapidstylers-mysql sh -c 'mysqldump -u root -p"$MYSQL_ROOT_PASSWORD" --single-transaction \
  --routines --triggers "$MYSQL_DATABASE"' > ~/backup-$(date +%Y%m%d-%H%M).sql
```

## Rolling back

**Roll back the app, not the schema.** V17 to V22 only add nullable columns, a
column with a default, and indexes. An older version of the app does not use the
new columns. `validate` only checks that the columns the app needs exist; extra
columns are fine. So putting the previous release back (move `main` back to the
previous commit and let CI deploy it) works without touching the database.

The one thing to know: V18's unique index stays, and older code never writes
the key, so it is always NULL there. MySQL allows any number of NULLs in a
unique index.

**Removing a migration** is only for a migration that is itself broken. Restore
the dump if you can. If you can't, undo it by hand and remove its history row so
Flyway runs it again later:

```sql
-- Example for V22. Each version's columns are in the table above.
ALTER TABLE stylers DROP COLUMN cover_image_url;
DELETE FROM flyway_schema_history WHERE version = '22';
```

Only do this with the app version that needs the column stopped, because
`validate` fails while the column is missing.

**If a migration fails part-way** (`success = 0` in `flyway_schema_history`),
the app won't start. Fix the cause, remove the failed row
(`DELETE FROM flyway_schema_history WHERE success = 0`), and redeploy. The
migrations check before they change anything, so running one again is safe.

## Writing a new migration

- Next free number, never edit one that has shipped.
- Additive only: new nullable columns, new tables, new indexes. A rename or drop
  is two releases: stop using it, then remove it.
- Guard each statement with an `information_schema` check, like V17 to V22.
- Add the matching field to the entity in the same commit, or `validate` fails.
