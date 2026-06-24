#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

export GOBIND=gobind-matsuri

# Inject the real sing-box version so the About screen shows it instead of "unknown".
# Upstream's Makefile sets constant.Version via read_tag; mirror that here. The sing-box
# source is cloned to ../sing-box by buildScript/lib/core/get_source.sh.
SING_BOX_VERSION=""
if [ -d ../sing-box ]; then
  SING_BOX_VERSION="$(cd ../sing-box && CGO_ENABLED=0 go run ./cmd/internal/read_tag 2>/dev/null || true)"
  if [ -z "$SING_BOX_VERSION" ]; then
    SING_BOX_VERSION="$(git -C ../sing-box describe --tags --always 2>/dev/null || true)"
  fi
fi
if [ -z "$SING_BOX_VERSION" ]; then
  echo ">> WARNING: could not determine sing-box version; About will show 'unknown'"
else
  echo ">> sing-box version: $SING_BOX_VERSION"
fi
VERSION_LDFLAG=""
if [ -n "$SING_BOX_VERSION" ]; then
  VERSION_LDFLAG="-X github.com/sagernet/sing-box/constant.Version=$SING_BOX_VERSION "
fi

# 16 KB page alignment (issue #1125): Android 15+ may use 16 KB memory pages, which
# requires native .so LOAD segments aligned to 16384. Force the external linker to use a
# 16 KB max/common page size so libgojni.so is aligned regardless of the gomobile/Go default.
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w ${VERSION_LDFLAG}-extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
