#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build-tests
xcrun swiftc -module-cache-path build-tests/cache Shared/*.swift Tunnel/DNS64Server.swift Tests/main.swift -o build-tests/protocol-tests
build-tests/protocol-tests
