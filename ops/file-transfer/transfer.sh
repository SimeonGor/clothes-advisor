#!/usr/bin/env bash
set -euo pipefail
umask 077

here=$(cd "$(dirname "$0")" && pwd)
psql_flags=(-X -q --set=ON_ERROR_STOP=1 --single-transaction)

snapshot() {
    psql "${psql_flags[@]}" --dbname="$1" --file="$here/export.sql"
}

case "${1:-}" in
    prepare)
        [[ $# == 2 ]] || { echo 'Usage: transfer.sh prepare SOURCE' >&2; exit 2; }
        psql "${psql_flags[@]}" --dbname="$2" --file="$here/prepare.sql"
        ;;
    export)
        [[ $# == 3 ]] || { echo 'Usage: transfer.sh export SOURCE NEW_MANIFEST' >&2; exit 2; }
        [[ ! -e "$3" && ! -L "$3" ]] || { echo 'Manifest path already exists' >&2; exit 1; }
        tmp=$(mktemp "$3.tmp.XXXXXX")
        trap 'rm -f "$tmp"' EXIT
        snapshot "$2" > "$tmp"
        ln "$tmp" "$3"
        ;;
    import)
        [[ $# == 4 ]] || { echo 'Usage: transfer.sh import SOURCE TARGET MANIFEST' >&2; exit 2; }
        tmp=$(mktemp "${TMPDIR:-/tmp}/photo-transfer.XXXXXX")
        trap 'rm -f "$tmp"' EXIT
        snapshot "$2" > "$tmp"
        if ! cmp -s "$4" "$tmp"; then
            echo 'Manifest differs from the current complete wardrobe snapshot or is unreadable' >&2
            exit 1
        fi
        # Feed the verified private snapshot, so replacing the supplied file cannot change the import.
        psql "${psql_flags[@]}" --dbname="$3" --file="$here/stage.sql" \
            --command="\copy pg_temp.source_photo FROM pstdin WITH (FORMAT text, ENCODING 'UTF8')" \
            --file="$here/import.sql" < "$tmp"
        ;;
    *)
        echo 'Usage: transfer.sh {prepare|export|import} ...' >&2
        exit 2
        ;;
esac
