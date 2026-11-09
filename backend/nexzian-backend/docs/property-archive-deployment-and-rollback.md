# Property archive (soft delete): deployment and rollback notes

Applies to the R3 change that turns `DELETE /api/v1/properties/{id}` into an archive and adds
`POST /api/v1/properties/{id}/restore` and `GET /api/v1/properties/archived`.

> The SQL below was written for PostgreSQL but was **not executed against a live PostgreSQL
> database** while preparing this change (the automated tests run on H2). Run it on a copy or
> staging database first.

## 1. What changes in the database

Two **nullable** columns are added to `properties`:

| Column       | Java field            | Expected PostgreSQL type | Meaning |
|--------------|-----------------------|--------------------------|---------|
| `deleted_at` | `Property.deletedAt`  | `timestamp(6)`           | `NULL` = active, set = archived (and when) |
| `deleted_by` | `Property.deletedBy`  | `uuid`                   | id of the user who archived it; no foreign key |

- No default, no `NOT NULL`, no backfill: every existing row is "active" the moment the column exists.
- No index on `deleted_at` (not needed at current volumes; see "Optional" below).
- `deleted_by` has no foreign key on purpose (same style as `tenant_id`): it is an audit value and
  must never block user maintenance. The cost is no database-level guarantee that the id exists.

## 2. How the columns get created

The application runs with `spring.jpa.hibernate.ddl-auto=update` (no Flyway/Liquibase). On the first
start of the new build Hibernate issues a plain `ALTER TABLE properties ADD COLUMN ...` for each
missing column. Adding a nullable column without a default is a quick, metadata-only change on
current PostgreSQL versions.

Requirements and cautions:
- The application's database user needs `ALTER` privilege on `properties`. If production forbids
  that, add the columns by hand **before** deploying (the statements are safe to re-run):

  ```sql
  ALTER TABLE properties ADD COLUMN IF NOT EXISTS deleted_at timestamp(6);
  ALTER TABLE properties ADD COLUMN IF NOT EXISTS deleted_by uuid;
  ```

- Mixed fleets: during a rolling deploy, instances still running the OLD build ignore the new
  columns. Do not let users archive properties until every instance runs the new build (see 4).

## 3. Deployment checklist

1. Back up the database (or at least the `properties` table).
2. (Only if `ALTER` is not granted to the app user) run the two `ALTER TABLE` statements above.
3. Deploy the new backend, then the new frontend. The old frontend still works against the new
   backend (its "Delete" simply archives), but shows no Archived view.
4. Verify:

   ```sql
   SELECT column_name, data_type FROM information_schema.columns
    WHERE table_name = 'properties' AND column_name IN ('deleted_at', 'deleted_by');
   SELECT count(*) AS archived FROM properties WHERE deleted_at IS NOT NULL;   -- expect 0 on day one
   ```

## 4. Rollback limitation (important)

**Rolling the application back to the pre-R3 build does NOT undo archiving, and it re-exposes
archived properties.** The old code does not know the columns exist:

- Archived properties reappear in the owner's property list and counts.
- Archived properties whose status is `PUBLISHED` become **publicly visible again**, including their
  images, because the old public queries filter only on `status = 'PUBLISHED'`.
- The old `DELETE` physically deletes rows again, and fails with a foreign-key error (HTTP 500)
  for any property that has images.

So a rollback is not just "redeploy the previous version". Also: **never drop the two columns** as
part of a rollback. They hold the archive information and are harmless to the old build.

### Safe rollback procedure

Do these **before** (or immediately with) redeploying the old build:

1. Stop users from archiving/restoring (maintenance window, or take the new build out of service).
2. Record the state you are about to change, so it can be restored when rolling forward again:

   ```sql
   CREATE TABLE properties_archive_rollback_backup AS
     SELECT id, status, deleted_at, deleted_by
       FROM properties WHERE deleted_at IS NOT NULL;
   ```

3. Make archived properties non-public for the old build, which only understands `status`:

   ```sql
   UPDATE properties SET status = 'DRAFT'
    WHERE deleted_at IS NOT NULL AND status = 'PUBLISHED';
   ```

   (Archived properties with other statuses were never public. They will show up in the owner's
   list under the old build; that is the price of the rollback.)
4. Redeploy the previous backend/frontend.

### Rolling forward again

1. Deploy the R3 build again.
2. Put the original statuses back from the backup so restored properties return as they were:

   ```sql
   UPDATE properties p SET status = b.status
     FROM properties_archive_rollback_backup b
    WHERE p.id = b.id AND p.deleted_at IS NOT NULL;
   ```

   Archived properties stay archived (their `deleted_at` was never touched), and publishing remains
   an explicit owner action.
3. Drop `properties_archive_rollback_backup` once verified.

Properties that the old build hard-deleted while it was running are gone; their leads keep a
dangling `property_id`. The R3 build handles that (see "legacy leads" below).

## 5. Related behaviour worth knowing

- **Legacy leads:** properties hard-deleted before archiving existed leave leads whose `property_id`
  matches nothing. The R3 build lets those leads be edited, keeps the dangling id until the user
  changes or clears it, and reports them as `propertyMissing = true` (distinct from archived).
- **No purge yet:** archived properties, their image rows and their image files stay until a
  separate, explicit purge feature exists. Disk usage for uploads therefore only grows.

## 6. Optional follow-up

If archived lists grow large, an index such as
`CREATE INDEX IF NOT EXISTS idx_properties_tenant_deleted ON properties (tenant_id, deleted_at);`
can be added. It is not required for correctness and was not added to keep this change free of
extra schema.
