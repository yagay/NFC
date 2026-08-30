from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

p=ROOT/'app/src/main/java/com/yagay/nfcdoorcard/xposed/NfcInjectionModule.java'
s=p.read_text()
old='''                boolean replacedExisting = rewritten.reason != null && rewritten.reason.contains("REPLACED_EXISTING_LA_NFCID1");\n                synchronized (this) {'''
new='''                boolean reversibleExisting = rewritten.reason != null &&\n                        (rewritten.reason.contains("REPLACED_EXISTING_LA_NFCID1") ||\n                                rewritten.reason.contains("RESIZED_EXISTING_LA_NFCID1"));\n                synchronized (this) {'''
if old not in s: raise SystemExit('replacedExisting declaration not found')
s=s.replace(old,new)
s=s.replace('''                    if (replacedExisting) {\n                        reversibleStockPayload = original.clone();''','''                    if (reversibleExisting) {\n                        // Both same-length replacement and structurally verified resize mutate an\n                        // existing LA_NFCID1 parameter. The exact original payload is therefore a\n                        // safe inverse, including stock 33 00 -> 33 04/07/0A transitions.\n                        reversibleStockPayload = original.clone();''')
p.write_text(s)

p=ROOT/'app/build.gradle.kts'
s=p.read_text()
s=s.replace('versionCode = 43','versionCode = 44')
s=s.replace('versionName = "1.0.42"','versionName = "1.0.43"')
s=s.replace('hook build 30; controller-epoch lifecycle verification + automatic OFF/ON reapply;',
              'hook build 31; reversible stock LA_NFCID1 resize + controller-epoch OFF/ON reapply;')
s=s.replace('buildConfigField("int", "HOOK_BUILD", "30")','buildConfigField("int", "HOOK_BUILD", "31")')
p.write_text(s)
