\set ON_ERROR_STOP on
\getenv app_user SPRING_DATASOURCE_USERNAME
\getenv app_password SPRING_DATASOURCE_PASSWORD
\getenv owner_user TIWALO_MIGRATION_USER
\getenv owner_password TIWALO_MIGRATION_PASSWORD
\getenv funding_password TIWALO_FUNDING_PASSWORD

BEGIN;
-- Refuse aliases between privileged, runtime and funding identities, including
-- accidental reuse of a V1 table owner as the HTTP login on an existing volume.
CREATE TEMP TABLE valid_role_configuration (valid boolean NOT NULL CHECK (valid));
INSERT INTO valid_role_configuration SELECT
    octet_length(:'app_user') BETWEEN 1 AND 63 AND octet_length(:'owner_user') BETWEEN 1 AND 63 AND
    :'app_user' <> :'owner_user' AND
    :'app_user' NOT IN ('postgres','tiwalo_runtime','tiwalo_dev_funder','tiwalo_local_seed') AND
    :'owner_user' NOT IN ('postgres','tiwalo_runtime','tiwalo_dev_funder','tiwalo_local_seed') AND
    NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'app_user'
                AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls)) AND
    NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tableowner = :'app_user');

SELECT format('CREATE ROLE %I NOLOGIN', name)
FROM (VALUES ('tiwalo_runtime'), ('tiwalo_dev_funder')) AS groups(name)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = name) \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', name, secret)
FROM (VALUES (:'owner_user', :'owner_password'), (:'app_user', :'app_password'),
             ('tiwalo_local_seed', :'funding_password')) AS logins(name, secret)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = name) \gexec

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"owner_user";
GRANT tiwalo_runtime TO :"app_user";
GRANT tiwalo_dev_funder TO tiwalo_local_seed;
INSERT INTO valid_role_configuration SELECT
    NOT pg_has_role(:'app_user', 'tiwalo_dev_funder', 'MEMBER') AND
    NOT pg_has_role(:'app_user', :'owner_user', 'MEMBER') AND
    NOT pg_has_role('tiwalo_local_seed', :'owner_user', 'MEMBER') AND
    NOT has_schema_privilege(:'app_user', 'public', 'CREATE') AND
    NOT has_schema_privilege('tiwalo_local_seed', 'public', 'CREATE');
COMMIT;
