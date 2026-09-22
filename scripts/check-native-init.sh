#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/tools" "$work/go/bin/nekobox-mobile" "$work/buildScript/lib/core"
export GOPATH="$work/go"
export GOMOBILE_COMMIT=fixture-commit
export MARKER="$work/invoked" INSTALL_MARKER="$work/install-invoked"
export PATH="$work/tools:$PATH"
cat > "$work/tools/go" <<'EOF'
#!/bin/bash
if [ "$*" = "env GOVERSION" ]; then
    echo go1.27.1
elif [ "$1" = install ]; then
    test "$GOBIN" = "$GOPATH/bin/nekobox-mobile" || exit 99
    test "$2" = "github.com/sagernet/gomobile/cmd/gomobile@$GOMOBILE_COMMIT" || exit 99
    test "$3" = "github.com/sagernet/gomobile/cmd/gobind@$GOMOBILE_COMMIT" || exit 99
    touch "$INSTALL_MARKER"
    exit 73
else
    exit 99
fi
EOF
printf '#!/bin/bash\ntouch "$MARKER"\nexit 99\n' > "$GOPATH/bin/nekobox-mobile/gomobile"
cp "$GOPATH/bin/nekobox-mobile/gomobile" "$GOPATH/bin/nekobox-mobile/gobind"
chmod +x "$work/tools/go" "$GOPATH/bin/nekobox-mobile/"*
printf '%s\n' "$GOMOBILE_COMMIT go1.27.1" > "$GOPATH/bin/nekobox-mobile/version"
cd "$work"

# A complete matching toolchain does not install or execute anything.
bash "$root/libcore/init.sh"
test ! -e "$MARKER"
test ! -e "$INSTALL_MARKER"

# A changed pin must not reuse old binaries or continue after installation fails.
if GOMOBILE_COMMIT=changed-commit bash "$root/libcore/init.sh"; then
    echo 'ERROR: a failed toolchain installation succeeded' >&2
    exit 1
fi
test -f "$INSTALL_MARKER"
test ! -e "$MARKER"
rm "$INSTALL_MARKER"

# Both generators must exist for the cache to be usable.
rm "$GOPATH/bin/nekobox-mobile/gobind"
if bash "$root/libcore/init.sh"; then
    echo 'ERROR: a missing gobind was accepted' >&2
    exit 1
fi
test -f "$INSTALL_MARKER"
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
