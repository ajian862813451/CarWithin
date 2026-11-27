#!/usr/bin/env python3
"""Verify the original 6.2 stack and the narrowly allowed IP discovery adapter."""
from hashlib import sha1, sha256
from pathlib import Path
import json
import re

root = Path(__file__).resolve().parents[1]
originals = root / 'Legacy62Original'
manifest = json.loads((originals / 'manifest.json').read_text(encoding='utf-8'))
for name, expected in manifest['original_files'].items():
    data = (originals / name).read_bytes().replace(b'\r\n', b'\n')
    assert sha256(data).hexdigest() == expected['sha256'], name
    assert sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == expected['git_blob'], name

def original_body(name):
    return '\n'.join(line for line in (originals / name).read_text(encoding='utf-8').splitlines()
                     if not line.startswith('import ')).strip() + '\n'

text = (root / 'App/Zeeho62Legacy.swift').read_text(encoding='utf-8')
blocks = {}
adapters = json.loads((originals / 'ae6-484-adapter.json').read_text(encoding='utf-8'))
bms_adapters = json.loads((originals / 'bms-overlay-adapter.json').read_text(encoding='utf-8'))
for name, changes in bms_adapters.items():
    adapters.setdefault(name, []).extend(changes)
presentation = json.loads((originals / 'presentation-adapter.json').read_text(encoding='utf-8'))
for name in ['errors']:
    adapters.setdefault(name, []).extend(presentation.get(name, []))
for name, expected in manifest['embedded_blocks'].items():
    start, end = '    // BEGIN ORIGINAL62 ' + name + '\n', '    // END ORIGINAL62 ' + name
    assert text.count(start) == 1 and text.count(end) == 1
    value = text.split(start, 1)[1].split(end, 1)[0]
    value = ''.join(line[4:] + '\n' for line in value.splitlines())
    for change in reversed(adapters.get(name, [])):
        assert change['adapted'] in value, (name, 'missing approved dimension/BMS adapter')
        value = value.replace(change['adapted'], change['original'])
    assert sha256(value.encode('utf-8')).hexdigest() == expected, name
    blocks[name] = value

assert blocks['output'] == original_body('ZeehoOutput.swift'), '6.2 output changed'
protocol = original_body('TailingProtocol.swift')
assert blocks['protocol'] == protocol[protocol.index('enum TailingProtocol'):protocol.index('extension Data')].strip() + '\n'
assert blocks['errors'] == protocol[protocol.index('enum TailingError'):].strip() + '\n'
connection = blocks['connection'].replace('private var targetIP: String', 'private let targetIP: String')
connection, count = re.subn(r'        var discoveredSocket: TailingSocket\?\n        if targetIP.isEmpty \{\n.*?\n        \}\n', '', connection, count=1, flags=re.S)
assert count == 1
connection, count = re.subn(r'        let announcement: TailingSocket\n        if let discoveredSocket.*?        defer \{ announcement.close\(\) \}\n',
    '        let announcement = try tracked(TailingSocket.create())\n        defer { announcement.close() }\n        try announcement.connect(localIP: localIP, remoteIP: targetIP, port: ports.announcement)\n', connection, count=1, flags=re.S)
assert count == 1
connection = connection.replace('        UserDefaults.standard.set(targetIP, forKey: TailingDiscovery.savedAddressKey)\n', '')
assert connection == original_body('TailingConnection.swift'), '6.2 negotiation changed beyond discovery adapter'
def without_localization(text):
    # Only remove the known CWL presentation wrapper; retain argument expressions.
    def expand(match):
        key, expressions = match.group(1), match.group(2)
        for index, expression in enumerate(re.findall(r'String\(describing: ([^)]+)\)', expressions), 1):
            key = key.replace('{' + str(index) + '}', '\\(' + expression + ')')
        return '"' + key + '"'
    text = re.sub(r'CWL\("([^"\n]*)", \[(.*?)\]\)', expand, text)
    return re.sub(r'CWL\(("[^"\n]*")\)', r'\1', text)

for name in manifest['shared_original_files']:
    shared_text = (root / 'App' / name).read_text(encoding='utf-8')
    for change in reversed(presentation.get('shared', {}).get(name, [])):
        assert change['adapted'] in shared_text, (name, 'missing presentation adapter')
        shared_text = shared_text.replace(change['adapted'], change['original'])
    assert without_localization(shared_text) == (originals / name).read_text(encoding='utf-8'), name
helpers = protocol[protocol.index('extension Data'):protocol.index('enum TailingError')].strip()
assert helpers in (root / 'App/TailingProtocol.swift').read_text(encoding='utf-8')
native = (root / 'App/NativeReceiver.swift').read_text(encoding='utf-8')
assert 'private let zeeho62 = Zeeho62Legacy.ZeehoOutput()' in native
assert 'self.zeeho62.start(' in native and 'self.zeeho62.append(' in native and 'zeeho62.stop()' in native
settings = (root / 'App/ReceiverViewController.swift').read_text(encoding='utf-8')
cases = re.search(r'private enum HeadUnitOutput: Int \{.*?\bcase ([^\n]+)', settings, re.S).group(1)
assert [name.strip() for name in cases.split(',')][:12] == [
    'browser', 'carLife', 'airPlay', 'tailing', 'ninebot', 'zeeho', 'ninebotSeven', 'ninebot43', 'zeehoSEPlus', 'zeehoAE2', 'cfmoto5', 'cfmoto62'
], 'persisted output IDs changed'
print('PASS: 6.2 baseline verified after reversing approved AE6 dimension and BMS overlay adapters; original negotiation restored with IP-discovery-only changes; raw snapshots and shared socket/identity verified (allowing presentation localization)')
print('PASS: original output IDs 0–11 preserved; new motorcycle profiles appended')

assert [name.strip() for name in cases.split(',')][:27] == [
    'browser', 'carLife', 'airPlay', 'tailing', 'ninebot', 'zeeho', 'ninebotSeven', 'ninebot43', 'zeehoSEPlus', 'zeehoAE2', 'cfmoto5', 'cfmoto62', 'cfmoto700MT', 'cfmoto800MT', 'cfmoto675SRR', 'benelliTRK', 'vogeDS800X', 'zontes368E', 'zontes368G', 'zontes703RR', 'qjSRT550', 'kove625X', 'moriniXCape1200', 'kove450Rally', 'kove800X', 'shouqu', 'zeehoAE4i'
], 'existing profile IDs 0-25 changed'
assert '.zeehoAE2, .zeehoAE4i, .cfmoto5' in settings, 'AE4i menu placement changed'
print('PASS: previous profile IDs 0-25 preserved; AE4i appended as 26 and displayed after AE2')
