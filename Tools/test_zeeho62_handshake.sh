#!/bin/bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
stage="$(mktemp -d /private/tmp/carwithin-zeeho62.XXXXXX)"
trap 'rm -rf "$stage"' EXIT
python3 - "$root" "$stage" <<'PY'
from pathlib import Path
import sys
root,stage=map(Path,sys.argv[1:]);source=(root/'App/Zeeho62Legacy.swift').read_text()
blocks=[]
for name in ['connection','protocol','errors']:
 blocks.append(source.split('    // BEGIN ORIGINAL62 '+name+'\n',1)[1].split('    // END ORIGINAL62 '+name,1)[0])
(stage/'LegacyConnection.swift').write_text('import Foundation\nenum Zeeho62Legacy {\n'+''.join(blocks)+'\n}\n')
tests=(root/'Tools/TailingProtocolSmoke.swift').read_text();a=tests.index('    static func main()');b=tests.index('    private static func frames()',a)
tests=tests[:a]+'''    static func main() throws {
        let identity = try TailingIdentity.generate()
        for height in [480, 488, 484] {
            let reported = InstrumentFrameSize(width: 1024, height: height)
            let output = InstrumentFrameSize(width: 1024, height: height == 484 ? 484 : 480)
            try simulatedInstrument(identity: identity, profile: .zeeho62, reported: reported, expected: output)
        }
        print("PASS: actual legacy TCP handshake, 480/488 compatibility, native AE6 484 media reply and encoder dimensions, frame pulling and heartbeat")
    }

'''+tests[b:]
a=tests.index('    private static func simulatedInstrument(');b=tests.index('    private static func rejectedInstrument(',a);fn=tests[a:b]
fn=fn.replace('TailingPorts(', 'Zeeho62Legacy.TailingPorts(').replace('TailingConnection(', 'Zeeho62Legacy.TailingConnection(').replace('TailingPhoneInfo(', 'Zeeho62Legacy.TailingPhoneInfo(').replace('profile: profile,','profile: .zeeho62,')
fn=fn.replace('let sizeForEncoder = try connection.start()','try connection.start()\n        let dimensions = connection.outputDimensions\n        let sizeForEncoder = InstrumentFrameSize(width: dimensions.width, height: dimensions.height)')
tests=tests[:a]+fn+tests[b:];(stage/'LegacyHandshakeSmoke.swift').write_text(tests)
PY
swiftc -swift-version 5 \
 "$root"/Sources/TDSCarKitCore/{Binary,VideoCodec,AnnexB,Localization}.swift \
 "$root"/App/Tailing*.swift "$root/App/Zeeho62MediaSize.swift" \
 "$stage/LegacyConnection.swift" "$stage/LegacyHandshakeSmoke.swift" -o "$stage/legacy-test"
"$stage/legacy-test"
