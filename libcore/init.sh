#!/bin/bash
set -euo pipefail

rm -rf .build
GOPATH="${GOPATH:-$(go env GOPATH)}"
GOMOBILE_COMMIT="${GOMOBILE_COMMIT:-9f03b8f25789099c5c8abef4a02085da783ba923}"
toolchain="$GOMOBILE_COMMIT $(go env GOVERSION)"
tool_dir="$GOPATH/bin/nekobox-mobile"
stamp="$tool_dir/version"

if [ ! -x "$tool_dir/gomobile" ] || [ ! -x "$tool_dir/gobind" ] ||
    ! cmp -s <(printf '%s\n' "$toolchain") "$stamp"; then
    mkdir -p "$tool_dir"
    GOBIN="$tool_dir" go install \
        "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_COMMIT" \
        "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_COMMIT"
    printf '%s\n' "$toolchain" > "$stamp"
fi

# bind initializes its Android environment itself. `gomobile init` installs an
# unpinned gobind@latest and is only needed here for optional OpenAL builds.
