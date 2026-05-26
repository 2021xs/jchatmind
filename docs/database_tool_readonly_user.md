# DataBaseTools Read-Only Database User

DataBaseTools must use a dedicated read-only database account through
`jchatmind.database-tool.datasource`. The main business datasource remains
configured by `spring.datasource` and is still used by normal business reads and
writes.

This account is only for the Agent `databaseQuery` tool. Do not grant it
`INSERT`, `UPDATE`, `DELETE`, `TRUNCATE`, `CREATE`, `DROP`, or `ALTER`
permissions.

The application layer still keeps `SqlSafetyValidator` / JSqlParser validation.
The database account is the permission-level fallback in case an application
validation rule misses a risky query.

## PostgreSQL Example

Replace `jchatmind`, `public`, and the password with the values for the target
environment. Do not commit real passwords.

```sql
CREATE USER jchatmind_readonly WITH PASSWORD 'change_me';

GRANT CONNECT ON DATABASE jchatmind TO jchatmind_readonly;
GRANT USAGE ON SCHEMA public TO jchatmind_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO jchatmind_readonly;

ALTER DEFAULT PRIVILEGES IN SCHEMA public
GRANT SELECT ON TABLES TO jchatmind_readonly;
```

If the project uses another schema, replace `public` with that schema name.

## Application Configuration

```yaml
jchatmind:
  database-tool:
    datasource:
      url: ${JCHATMIND_DB_READONLY_URL:jdbc:postgresql://localhost:5432/jchatmind}
      username: ${JCHATMIND_DB_READONLY_USERNAME:jchatmind_readonly}
      password: ${JCHATMIND_DB_READONLY_PASSWORD:}
      driver-class-name: org.postgresql.Driver
```

`JCHATMIND_DB_READONLY_PASSWORD` must be provided in the runtime environment.
The application should not silently fall back to the main business database
account.

## Manual Verification

Use the read-only account directly:

```sql
SELECT 1;
UPDATE some_table SET some_column = some_column;
DELETE FROM some_table WHERE false;
```

`SELECT 1` should work. The write statements should be rejected by PostgreSQL
permissions. In normal Agent execution, non-SELECT SQL is rejected earlier by
`SqlSafetyValidator`; these write statements are only for verifying the database
permission fallback.
