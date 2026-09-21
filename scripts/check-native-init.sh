#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/tools" "$work/go/bin" "$work/buildScript/lib/core"
export GOPATH="$work/go"
export GOMOBILE_COMMIT=fixture-commit
export MARKER="$work/invoked"
export PATH="$work/tools:$PATH"
printf '#!/bin/bash\nif [ "$*" = "env GOVERSION" ]; then echo go1.26.8; else exit 99; fi\n' > "$work/tools/go"
printf '#!/bin/bash\nexit 73\n' > "$work/tools/git"
printf '#!/bin/bash\nprintf "invoked\\n" >> "$MARKER"\n' > "$GOPATH/bin/gomobile-matsuri"
cp "$GOPATH/bin/gomobile-matsuri" "$GOPATH/bin/gobind-matsuri"
chmod +x "$work/tools/"* "$GOPATH/bin/"*
printf '%s\n' "$GOMOBILE_COMMIT go1.26.8" > "$GOPATH/bin/gomobile-matsuri.version"
cd "$work"

bash "$root/libcore/init.sh"
test -f "$MARKER"
rm "$MARKER"

# A changed pin must not reuse old binaries or continue after checkout failure.
if GOMOBILE_COMMIT=changed-commit bash "$root/libcore/init.sh"; then
    echo 'ERROR: a failed toolchain checkout succeeded' >&2
    exit 1
fi
test ! -e "$MARKER"

# Both generators must exist for the cache to be usable.
rm "$GOPATH/bin/gobind-matsuri"
if bash "$root/libcore/init.sh"; then
    echo 'ERROR: a missing gobind was accepted' >&2
    exit 1
fi
test ! -e "$MARKER"

# The outer entry point must not build after initialization fails.
printf '#!/bin/bash\nexit 73\n' > buildScript/lib/core/init.sh
printf '#!/bin/bash\ntouch "$MARKER"\n' > buildScript/lib/core/build.sh
chmod +x buildScript/lib/core/*.sh
if bash "$root/buildScript/lib/core.sh"; then
    echo 'ERROR: core build continued after initialization failed' >&2
    exit 1
fi
test ! -e "$MARKER"
echo 'Native initialization checks passed.'
