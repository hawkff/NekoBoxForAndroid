#!/usr/bin/env bash
set -e

source "buildScript/init/env.sh"

# Fetch the pinned core sources.
bash buildScript/lib/core/get_source.sh

[ -f libcore/go.mod ] || exit 1
cd libcore

./init.sh || exit 1
