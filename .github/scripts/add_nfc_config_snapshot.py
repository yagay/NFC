from pathlib import Path

p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()

old = '''        appendLine("--- NFC PROCESS / HAL ---"); appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC SERVICE FULL ---")
'''
new = '''        appendLine("--- NFC PROCESS / HAL ---"); appendLine(runRootCmd("pm path com.android.nfc; pidof com.android.nfc; ps -A | grep -E 'android.hardware.nfc|vendor.oplus.hardware.nfc|com.android.nfc|$packageName'"))
        appendLine("--- NFC CONFIG SNAPSHOT ---")
        appendLine(collectNfcConfigSnapshot())
        appendLine("--- NFC SERVICE FULL ---")
'''
if s.count(old) != 1:
    raise SystemExit(f'report marker count={s.count(old)}')
s = s.replace(old, new, 1)

marker = '''    private fun ensureRootAccess(showToast: Boolean = true): Boolean {
'''
insert = r'''    private fun collectNfcConfigSnapshot(): String {
        val script = """
            echo '--- BUILD / NFC IDENTITY ---'
            getprop ro.product.manufacturer
            getprop ro.product.device
            getprop ro.build.fingerprint
            getprop ro.boot.hardware
            pm path com.android.nfc 2>/dev/null || true
            dumpsys package com.android.nfc 2>/dev/null | grep -E 'versionName=|versionCode=' | head -n 10 || true
            echo '--- NFC CONFIG FILES ---'
            roots='/vendor/etc /odm/etc /product/etc /system/etc /my_product/etc'
            files=""
            for root in ${'$'}roots; do
              [ -d "${'$'}root" ] || continue
              found=${'$'}(find "${'$'}root" -maxdepth 5 -type f \
                \( -iname '*nfc*.conf' -o -iname '*nfc*.cfg' -o -iname '*nfc*.xml' -o \
                   -iname '*nfc*.txt' -o -iname '*nfc*.json' -o -iname '*nfc*.properties' -o \
                   -path '*/nfc/*.conf' -o -path '*/nfc/*.cfg' -o -path '*/nfc/*.xml' \) \
                -size -262144c 2>/dev/null | sort -u | head -n 80)
              files="${'$'}files
${'$'}found"
            done
            echo "${'$'}files" | sed '/^${'$'}/d' | sort -u | while IFS= read -r f; do
              [ -f "${'$'}f" ] || continue
              echo
              echo "===== FILE: ${'$'}f ====="
              ls -lZ "${'$'}f" 2>/dev/null || ls -l "${'$'}f" 2>/dev/null || true
              cat "${'$'}f" 2>/dev/null || echo '[read failed]'
            done
        """.trimIndent()
        return runRootCmd(script, 25, 400_000)
    }

'''
if s.count(marker) != 1:
    raise SystemExit(f'ensureRootAccess marker count={s.count(marker)}')
s = s.replace(marker, insert + marker, 1)
p.write_text(s)
