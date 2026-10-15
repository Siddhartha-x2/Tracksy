#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=app_password="$DB_PASSWORD" --set=ON_ERROR_STOP=1 <<'SQL'
CREATE ROLE tracksy LOGIN PASSWORD :'app_password';
ALTER DATABASE tracksy OWNER TO tracksy;
SQL
