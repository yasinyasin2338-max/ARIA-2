from pathlib import Path

root = Path('nova-avatar-build')
parser = root / 'app/src/main/java/com/arisnova/avatar/engine/CommandParser.java'
test = root / 'app/src/test/java/com/arisnova/avatar/engine/CommandParserTest.java'
gradle = root / 'app/build.gradle'

s = parser.read_text(encoding='utf-8')
old = 'if (containsAny(s, "برگرد حال", "برو حال", "برو تو حال", "برو داخل حال", "برو سالن"))'
new = 'if (containsAny(s, "برگرد حال", "برو حال", "برو تو حال", "برو داخل حال", "برو سالن", "برگرد پذیرایی", "برو پذیرایی", "برو تو پذیرایی", "برو سالن اصلی"))'
if old not in s:
    raise SystemExit('parser baseline not found')
parser.write_text(s.replace(old, new), encoding='utf-8')

s = test.read_text(encoding='utf-8')
anchor = '\n    @Test public void parsesLookDirections() {'
insert = '''\n    @Test public void parsesLivingRoomColloquialVariants() {\n        assertEquals(Room.LIVING, parser.parse("برگرد پذیرایی").get(0).room);\n        assertEquals(Room.LIVING, parser.parse("برو سالن اصلی").get(0).room);\n        List<Action> actions = parser.parse("برگرد پذیرایی و بشین");\n        assertEquals(2, actions.size());\n        assertEquals(IntentType.GO_ROOM, actions.get(0).type);\n        assertEquals(IntentType.SIT, actions.get(1).type);\n    }\n'''
if 'parsesLivingRoomColloquialVariants' not in s:
    if anchor not in s:
        raise SystemExit('test anchor not found')
    s = s.replace(anchor, insert + anchor)
    test.write_text(s, encoding='utf-8')

s = gradle.read_text(encoding='utf-8')
s = s.replace('versionCode 3', 'versionCode 4').replace("versionName '0.3.0-dev'", "versionName '0.3.1-dev'")
gradle.write_text(s, encoding='utf-8')

print('STEP_031_PATCH_APPLIED')
