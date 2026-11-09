#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C
unset PGHOSTADDR PGSERVICE

tests=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$tests/../../.." && pwd)
scratch=$(mktemp -d /tmp/photo-transfer-test.XXXXXX)
cleanup() {
    for side in source target; do
        if [[ -f "$scratch/$side/data/postmaster.pid" ]]; then
            pg_ctl -s -D "$scratch/$side/data" -m fast -w stop
        fi
    done
    rm -rf "$scratch"
}
trap cleanup EXIT

for side in source target; do
    mkdir "$scratch/$side"
    initdb -D "$scratch/$side/data" -U transfer_test --locale=C -E UTF8 -A trust > "$scratch/$side/init.log"
    pg_ctl -s -w -D "$scratch/$side/data" -l "$scratch/$side/server.log" \
        -o "-h '' -k '$scratch/$side' -p 5432" start
done
source_db="host=$scratch/source port=5432 user=transfer_test dbname=postgres"
target_db="host=$scratch/target port=5432 user=transfer_test dbname=postgres"
sql() { psql -XqAt --set=ON_ERROR_STOP=1 --dbname="$1" --command="$2"; }
script() { psql -Xq --set=ON_ERROR_STOP=1 --dbname="$1" --file="$2"; }
transfer() { bash "$tests/../transfer.sh" "$@"; }
reject() {
    local pattern=$1
    shift
    if "$@" > "$scratch/rejected.log" 2>&1; then
        echo "Unexpected success; expected $pattern" >&2
        exit 1
    fi
    grep -Eq "$pattern" "$scratch/rejected.log" || { cat "$scratch/rejected.log" >&2; exit 1; }
}
target_snapshot() {
    sql "$target_db" "COPY (SELECT * FROM public.file_object ORDER BY file_id) TO STDOUT"
}

migrations="$root/wardrobe-service/src/main/resources/db/migration"
for ((version=1; version<=21; version++)); do
    script "$source_db" "$migrations/V$version"__*.sql
done
sql "$source_db" "INSERT INTO public.wardrobe_item (id, owner_id, category_id, name, color, material, version)
                  VALUES (70, 123, 1, 'coat', 'blue', 'wool', 1);
                  INSERT INTO public.wardrobe_item_photo (id, wardrobe_item_id, s3_key, content_type, size_bytes)
                  VALUES (1, 70, 'before-v22', 'image/jpeg', 2)"
script "$source_db" "$migrations/V22__add_photo_file_id.sql"
[[ $(sql "$source_db" 'SELECT file_id IS NULL FROM public.wardrobe_item_photo WHERE id = 1') == t ]]
sql "$source_db" 'DELETE FROM public.wardrobe_item_photo WHERE id = 1'
script "$target_db" "$root/file-service/src/main/resources/db/migration/V1__create_file_object.sql"
sql "$target_db" "INSERT INTO public.file_object VALUES
    ('00000000-0000-4000-8000-000000000001', 'outside-import', 'image/jpeg', 20, '2026-01-15 01:02:03+00')"
target_snapshot > "$scratch/original-target"

transfer prepare "$source_db"
transfer export "$source_db" "$scratch/empty"
[[ ! -s "$scratch/empty" ]]
transfer import "$source_db" "$target_db" "$scratch/empty"
target_snapshot > "$scratch/after-empty"
cmp "$scratch/original-target" "$scratch/after-empty"
echo 'PASS empty source with unrelated target row'

script "$source_db" "$tests/photos.sql"
reject 'duplicate key' sql "$source_db" "UPDATE public.wardrobe_item_photo
    SET file_id = '00000000-0000-4000-8000-000000000031' WHERE id = 11"
reject 'Photo without file_id' transfer export "$source_db" "$scratch/too-early"
[[ ! -e "$scratch/too-early" ]]
sql "$source_db" "UPDATE public.wardrobe_item_photo SET content_type = 'image/gif' WHERE id = 31"
reject 'Photo 31 has incompatible MIME or size' transfer prepare "$source_db"
[[ $(sql "$source_db" 'SELECT count(*) FROM public.wardrobe_item_photo WHERE file_id IS NULL') == 2 ]]
sql "$source_db" "UPDATE public.wardrobe_item_photo SET content_type = 'image/png' WHERE id = 31"

# Simulate an inconsistent legacy schema so the transfer's own size preflight is exercised.
sql "$source_db" 'ALTER TABLE public.wardrobe_item_photo DROP CONSTRAINT wardrobe_item_photo_size_valid'
for bad_size in 0 10000001; do
    sql "$source_db" "UPDATE public.wardrobe_item_photo SET size_bytes = $bad_size WHERE id = 21"
    reject 'Photo 21 has incompatible MIME or size' transfer prepare "$source_db"
    [[ $(sql "$source_db" 'SELECT count(*) FROM public.wardrobe_item_photo WHERE file_id IS NULL') == 2 ]]
done
sql "$source_db" 'UPDATE public.wardrobe_item_photo SET size_bytes = 1 WHERE id = 21;
    ALTER TABLE public.wardrobe_item_photo ADD CONSTRAINT wardrobe_item_photo_size_valid
    CHECK (size_bytes > 0 AND size_bytes <= 10000000)'
echo 'PASS legacy inserts, uniqueness and preflight rollback'

original_rows="COPY (SELECT id, wardrobe_item_id, s3_key, content_type, size_bytes, created_at
                     FROM public.wardrobe_item_photo ORDER BY id) TO STDOUT"
sql "$source_db" "$original_rows" > "$scratch/original-photos"
manifest="$scratch/manifest"
transfer prepare "$source_db"
transfer export "$source_db" "$manifest"
transfer prepare "$source_db"
transfer export "$source_db" "$scratch/restart"
cmp "$manifest" "$scratch/restart"
sql "$source_db" "$original_rows" > "$scratch/prepared-photos"
cmp "$scratch/original-photos" "$scratch/prepared-photos"
[[ $(sql "$source_db" 'SELECT count(DISTINCT file_id) FROM public.wardrobe_item_photo') == 3 ]]
[[ $(sql "$source_db" 'SELECT file_id FROM public.wardrobe_item_photo WHERE id = 31') == 00000000-0000-4000-8000-000000000031 ]]
reject 'Manifest path already exists' transfer export "$source_db" "$manifest"
echo 'PASS restart after source commit preserves all ids, keys and dates'

sed '$d' "$manifest" > "$scratch/truncated"
for bad_manifest in "$scratch/empty" "$scratch/truncated"; do
    reject 'Manifest differs' transfer import "$source_db" "$target_db" "$bad_manifest"
done
sql "$source_db" 'UPDATE public.wardrobe_item_photo SET size_bytes = 2 WHERE id = 21'
reject 'Manifest differs' transfer import "$source_db" "$target_db" "$manifest"
sql "$source_db" 'UPDATE public.wardrobe_item_photo SET size_bytes = 1 WHERE id = 21'
target_snapshot > "$scratch/after-rejections"
cmp "$scratch/original-target" "$scratch/after-rejections"
echo 'PASS empty, fully truncated row and stale same-count manifest rejected'

known_id=00000000-0000-4000-8000-000000000031
insert_match="INSERT INTO public.file_object VALUES ('$known_id', 'legacy/pinned.png',
                                                        'image/png', 19, '2025-06-07 08:09:10+00')"
for mismatch in "object_key = 'other'" "file_id = '00000000-0000-4000-8000-000000000099'" \
                "content_type = 'image/jpeg'" "size_bytes = 18" "created_at = created_at + interval '1 microsecond'"; do
    sql "$target_db" "$insert_match; UPDATE public.file_object SET $mismatch WHERE file_id = '$known_id'"
    target_snapshot > "$scratch/before-conflict"
    reject 'duplicate key|metadata does not match' transfer import "$source_db" "$target_db" "$manifest"
    target_snapshot > "$scratch/after-conflict"
    cmp "$scratch/before-conflict" "$scratch/after-conflict"
    sql "$target_db" "DELETE FROM public.file_object WHERE object_key <> 'outside-import'"
done
echo 'PASS conflicts on UUID, key, MIME, size or timestamp roll back the entire target import'

sql "$target_db" "$insert_match"
transfer import "$source_db" "$target_db" "$manifest"
target_snapshot > "$scratch/imported"
transfer prepare "$source_db"
transfer import "$source_db" "$target_db" "$manifest"
target_snapshot > "$scratch/repeated"
cmp "$scratch/imported" "$scratch/repeated"
sql "$source_db" 'COPY (SELECT file_id, s3_key, content_type, size_bytes, created_at
    FROM public.wardrobe_item_photo ORDER BY file_id) TO STDOUT' > "$scratch/expected-files"
sql "$target_db" "COPY (SELECT * FROM public.file_object
    WHERE object_key <> 'outside-import' ORDER BY file_id) TO STDOUT" > "$scratch/actual-files"
cmp "$scratch/expected-files" "$scratch/actual-files"
sql "$target_db" "COPY (SELECT * FROM public.file_object
    WHERE object_key = 'outside-import') TO STDOUT" > "$scratch/unrelated"
cmp "$scratch/original-target" "$scratch/unrelated"
sql "$source_db" "$original_rows" > "$scratch/final-photos"
cmp "$scratch/original-photos" "$scratch/final-photos"
echo 'PASS exact metadata, special characters, unrelated row and restart after target commit'
