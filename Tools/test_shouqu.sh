#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
stage="$(mktemp -d /private/tmp/carwithin-shouqu.XXXXXX)"
trap 'rm -rf "$stage"' EXIT
swiftc -swift-version 5 "$root"/Sources/TDSCarKitCore/{Binary,Localization}.swift \
 "$root"/App/Tailing{Socket,Protocol,Identity,DisplayProfile}.swift "$root/App/Zeeho62MediaSize.swift" \
 "$root/App/ShouquProtocol.swift" "$root/App/ShouquConnection.swift" "$root/Tools/ShouquProtocolSmoke.swift" -o "$stage/test"
"$stage/test"
