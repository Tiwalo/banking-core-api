\getenv app_user SPRING_DATASOURCE_USERNAME
\getenv app_password SPRING_DATASOURCE_PASSWORD

CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password';
GRANT CONNECT ON DATABASE banking_core TO :"app_user";
GRANT USAGE, CREATE ON SCHEMA public TO :"app_user";
