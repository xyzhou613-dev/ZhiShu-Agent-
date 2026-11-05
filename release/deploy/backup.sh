#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
umask 077
mkdir -p "$project_dir/backups"
backup_file="$project_dir/backups/bemodel-$(date +%Y%m%d-%H%M%S).sql.gz"
sql_file="${backup_file%.gz}"
trap 'rm -f -- "$sql_file"' EXIT HUP INT TERM
database_list=$("$project_dir/compose.sh" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -Nse "SHOW DATABASES"')
db_names=$(printf '%s\n' "$database_list" | grep -E '^(bemodel_platform|bemodel_lab|demo_(his|lis|charge|pharmacy|opd|emr|pacs|nurse|material))$')
# Names are restricted to the fixed allowlist above; intentional word splitting.
"$project_dir/compose.sh" exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysqldump -uroot --single-transaction --quick --routines --triggers --events --set-gtid-purged=OFF --no-tablespaces --databases "$@"' sh $db_names > "$sql_file"
gzip -c "$sql_file" > "$backup_file"
gzip -t "$backup_file"
echo "$backup_file"
