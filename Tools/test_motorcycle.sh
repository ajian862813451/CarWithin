#!/bin/bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
work_dir="$(mktemp -d /private/tmp/carwithin-motorcycle.XXXXXX)"
trap 'rm -rf "$work_dir"' EXIT
swiftc -swift-version 5 \
  "$project_root/Sources/TDSCarKitCore/Localization.swift" \
  "$project_root/App/TailingSocket.swift" "$project_root/App/TailingProtocol.swift" "$project_root/App/TailingDisplayProfile.swift" "$project_root/App/Zeeho62MediaSize.swift" "$project_root/Sources/TDSCarKitCore/Binary.swift" \
  "$project_root/App/EryaProtocol.swift" "$project_root/App/EryaConnection.swift" \
  "$project_root/App/YunmoProtocol.swift" "$project_root/App/YunmoConnection.swift" \
  "$project_root/App/ThinkerRideProtocol.swift" "$project_root/App/ThinkerRideConnection.swift" "$project_root/App/MotorcycleProfile.swift" \
  "$project_root/Tools/MotorcycleProtocolSmoke.swift" -o "$work_dir/motorcycle-test"
"$work_dir/motorcycle-test"
