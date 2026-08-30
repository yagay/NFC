from pathlib import Path

root = Path('app/src/main/java/com/example/nfcdoorcard')

# Correct framework package for ContentObserver.
p = root / 'RuntimeStatusRepository.kt'
s = p.read_text().replace('import android.content.ContentObserver', 'import android.database.ContentObserver')
p.write_text(s)

# Shell variables inside Kotlin raw strings must be emitted literally; only $reason is Kotlin interpolation.
p = root / 'system/NfcSystemService.kt'
s = p.read_text()
for token in ['$before', '$roots', '$root', '$files', '$found', '$state', '$old', '$new', '$i', '$j', '$f', '$1']:
    s = s.replace(token, "${'$'}" + token[1:])
s = s.replace('$(', "${'$'}(")
p.write_text(s)

# Safe-call expression yields Unit?; make the dispatcher API explicitly Result<Unit>.
p = root / 'nfc/NfcForegroundDispatcher.kt'
s = p.read_text()
s = s.replace(
    'fun enable(): Result<Unit> = runCatching {\n            adapter?.enableForegroundDispatch(activity, pendingIntent, null, null)\n        }',
    'fun enable(): Result<Unit> = runCatching {\n            adapter?.enableForegroundDispatch(activity, pendingIntent, null, null)\n            Unit\n        }'
)
p.write_text(s)

# Restore the small Activity delegate accidentally removed during extraction.
p = root / 'MainActivity.kt'
s = p.read_text()
if 'private fun enableReadDispatch()' not in s:
    marker = '    private fun disableReadDispatch() { nfcDispatcher.disable() }\n'
    insert = '''    private fun enableReadDispatch() {\n        if (!readModeEnabled) return\n        nfcDispatcher.enable()\n            .onSuccess { AppLogger.i("READ_MODE: foreground dispatch enabled") }\n            .onFailure { AppLogger.i("READ_MODE: enable dispatch failed ${it.javaClass.simpleName}: ${it.message}") }\n    }\n\n'''
    if marker not in s:
        raise SystemExit('disableReadDispatch marker missing')
    s = s.replace(marker, insert + marker, 1)
p.write_text(s)

# BuildConfig in a string template needs braces after replacing the old constant.
p = root / 'ui/NfcComponents.kt'
s = p.read_text().replace('$BuildConfig.HOOK_BUILD', '${BuildConfig.HOOK_BUILD}')
p.write_text(s)
