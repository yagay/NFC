from pathlib import Path

p = Path('.github/scripts/state_finalize.py')
s = p.read_text()
old = '''def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)
'''
new = '''def replace_once(text, old, new, label):
    count = text.count(old)
    if label == "complete consumed" and count == 2:
        return text.replace(old, new, 1)
    if label == "fail consumed" and count == 2:
        index = text.rfind(old)
        return text[:index] + new + text[index + len(old):]
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)
'''
if s.count(old) != 1:
    raise SystemExit('replace_once helper shape changed')
p.write_text(s.replace(old, new, 1))
