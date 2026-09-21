#!/bin/bash
set -euo pipefail

rm -rf .build
GOPATH="${GOPATH:-$(go env GOPATH)}"
GOMOBILE_COMMIT="${GOMOBILE_COMMIT:-17d6af34f6bd6d7e1e428e0c652c8b54a46bda4f}"
toolchain="$GOMOBILE_COMMIT $(go env GOVERSION)"
stamp="$GOPATH/bin/gomobile-matsuri.version"

if [ ! -x "$GOPATH/bin/gomobile-matsuri" ] || [ ! -x "$GOPATH/bin/gobind-matsuri" ] ||
    ! cmp -s <(printf '%s\n' "$toolchain") "$stamp"; then
    source_dir="$(mktemp -d)"
    trap 'rm -rf "$source_dir"' EXIT
    git -C "$source_dir" init -q
    git -C "$source_dir" remote add origin https://github.com/MatsuriDayo/gomobile.git
    git -C "$source_dir" fetch --depth 1 origin "$GOMOBILE_COMMIT"
    git -C "$source_dir" checkout -q FETCH_HEAD
    (
        cd "$source_dir"
        GOBIN="$source_dir/bin" go install ./cmd/gomobile ./cmd/gobind
    )
    mkdir -p "$GOPATH/bin"
    install -m 755 "$source_dir/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    install -m 755 "$source_dir/bin/gobind" "$GOPATH/bin/gobind-matsuri"
    printf '%s\n' "$toolchain" > "$stamp"
fi

GOBIND="$GOPATH/bin/gobind-matsuri" "$GOPATH/bin/gomobile-matsuri" init
