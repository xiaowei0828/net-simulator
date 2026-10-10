#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=d9dca26c7ad0e494492244f0309e80ee583e739e
destination=third_party/hev-socks5-tunnel
if [[ ! -d "$destination" ]]; then
  git clone https://github.com/heiher/hev-socks5-tunnel.git "$destination"
  git -C "$destination" checkout --detach "$revision"
fi
if [[ "$(git -C "$destination" rev-parse HEAD)" != "$revision" ]]; then
  echo 'Unexpected native dependency revision; preserve local changes and check out the revision in this script.' >&2
  exit 1
fi
git -C "$destination" submodule update --init --recursive

