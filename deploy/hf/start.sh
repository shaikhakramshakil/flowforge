#!/bin/bash
# FlowForge all-in-one start: local Postgres + engine + embedded worker.
# Used by the Hugging Face Space image (see /tmp/spaces/flowforge) and any
# plain `docker run -p 7860:7860` host. Postgres data is ephemeral.
set -e

export PGDATA=/tmp/pgdata
PGBIN=/usr/lib/postgresql/16/bin
DB=flowforge
DBUSER=flowforge
DBPASS=flowforge

as_pg() { su postgres -s /bin/bash -c "$*"; }

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  as_pg "$PGBIN/initdb -D $PGDATA -E UTF8"
fi
as_pg "$PGBIN/pg_ctl -D $PGDATA -l /tmp/pg.log -o '-k /tmp -p 5432' start"
until as_pg "$PGBIN/pg_isready -h /tmp -p 5432" >/dev/null 2>&1; do sleep 1; done
as_pg "psql -h /tmp -tc \"SELECT 1 FROM pg_roles WHERE rolname='$DBUSER'\" | grep -q 1 || $PGBIN/createuser -h /tmp $DBUSER"
as_pg "psql -h /tmp -tc \"SELECT 1 FROM pg_database WHERE datname='$DB'\" | grep -q 1 || $PGBIN/createdb -h /tmp -O $DBUSER $DB"
as_pg "psql -h /tmp -c \"ALTER USER $DBUSER PASSWORD '$DBPASS'\""

export FF_DB_URL="jdbc:postgresql://localhost:5432/$DB"
export FF_DB_USER="$DBUSER"
export FF_DB_PASSWORD="$DBPASS"

exec java -Xmx1g -jar /app/app.jar
