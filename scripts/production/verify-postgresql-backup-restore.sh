#!/usr/bin/env bash
set -euo pipefail

# Reproducible pre-production gate for a Firefly PostgreSQL logical backup.
# The restore target must be a separate, empty, disposable database.
: "${FIREFLY_BACKUP_SOURCE_URL:?set FIREFLY_BACKUP_SOURCE_URL to the quiesced source database URL}"
: "${FIREFLY_BACKUP_RESTORE_URL:?set FIREFLY_BACKUP_RESTORE_URL to an empty disposable database URL}"
: "${FIREFLY_BACKUP_SOURCE_QUIESCED:?set FIREFLY_BACKUP_SOURCE_QUIESCED=true after pausing Firefly writes}"

if [[ "${FIREFLY_BACKUP_SOURCE_QUIESCED}" != "true" ]]; then
  echo "Refusing to verify a changing source; pause Firefly writes and set FIREFLY_BACKUP_SOURCE_QUIESCED=true." >&2
  exit 2
fi
if [[ "${FIREFLY_BACKUP_SOURCE_URL}" == "${FIREFLY_BACKUP_RESTORE_URL}" ]]; then
  echo "Restore target must be different from the source database." >&2
  exit 2
fi

for command_name in pg_dump pg_restore psql sha256sum; do
  command -v "${command_name}" >/dev/null || {
    echo "Missing required command: ${command_name}" >&2
    exit 2
  }
done

existing_tables="$(psql "${FIREFLY_BACKUP_RESTORE_URL}" -X -v ON_ERROR_STOP=1 -Atqc \
  "select count(*) from information_schema.tables where table_schema = 'public'")"
if [[ "${existing_tables}" != "0" ]]; then
  echo "Restore target is not empty; refusing to overwrite ${existing_tables} public tables." >&2
  exit 2
fi

work_directory="$(mktemp -d)"
trap 'rm -rf -- "${work_directory}"' EXIT
backup_file="${work_directory}/firefly.dump"

pg_dump "${FIREFLY_BACKUP_SOURCE_URL}" \
  --format=custom --no-owner --no-privileges --file="${backup_file}"
sha256sum "${backup_file}"
pg_restore --list "${backup_file}" >/dev/null
pg_restore "${backup_file}" --dbname="${FIREFLY_BACKUP_RESTORE_URL}" \
  --exit-on-error --no-owner --no-privileges

tables=(
  firefly_schema_version
  firefly_job
  firefly_execution
  firefly_execution_target
  firefly_dispatch_outbox
  firefly_trigger_inbox
  firefly_trigger_nonce
  firefly_audit_log
)

for table_name in "${tables[@]}"; do
  source_count="$(psql "${FIREFLY_BACKUP_SOURCE_URL}" -X -v ON_ERROR_STOP=1 -Atqc \
    "select count(*) from ${table_name}")"
  restored_count="$(psql "${FIREFLY_BACKUP_RESTORE_URL}" -X -v ON_ERROR_STOP=1 -Atqc \
    "select count(*) from ${table_name}")"
  if [[ "${source_count}" != "${restored_count}" ]]; then
    echo "Row-count mismatch for ${table_name}: source=${source_count}, restored=${restored_count}" >&2
    exit 1
  fi
  echo "verified ${table_name}: ${restored_count} rows"
done

source_schema="$(psql "${FIREFLY_BACKUP_SOURCE_URL}" -X -v ON_ERROR_STOP=1 -Atqc \
  "select string_agg(version::text, ',' order by version) from firefly_schema_version")"
restored_schema="$(psql "${FIREFLY_BACKUP_RESTORE_URL}" -X -v ON_ERROR_STOP=1 -Atqc \
  "select string_agg(version::text, ',' order by version) from firefly_schema_version")"
if [[ "${source_schema}" != "${restored_schema}" ]]; then
  echo "Schema-version mismatch: source=${source_schema}, restored=${restored_schema}" >&2
  exit 1
fi

echo "Firefly PostgreSQL backup/restore verification passed (schema versions: ${restored_schema})."
