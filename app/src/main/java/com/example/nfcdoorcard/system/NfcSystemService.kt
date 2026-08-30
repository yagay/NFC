package com.example.nfcdoorcard.system

    class NfcSystemService(private val rootShell: RootShell) {
        fun currentNfcPid(): String = rootShell.run(
            "pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}'",
            timeoutSeconds = 5,
            maxChars = 4096,
            showToast = false
        ).lineSequence().firstOrNull { it.trim().matches(Regex("\\d+")) }?.trim().orEmpty()

        fun restartNfcProcessKeepingEnabled(reason: String): String {
            val script = """
                old=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
                before=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                echo "REASON=$reason"
                echo "OLD_PID=${'$'}old"
                echo "BEFORE_STATE=${'$'}before"
                if [ -n "${'$'}old" ]; then
                  kill -TERM "${'$'}old" 2>/dev/null || true
                  sleep 0.5
                  kill -0 "${'$'}old" 2>/dev/null && kill -KILL "${'$'}old" 2>/dev/null || true
                fi
                i=0; new=""
                while [ ${'$'}i -lt 60 ]; do
                  new=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
                  if [ -n "${'$'}new" ] && [ "${'$'}new" != "${'$'}old" ]; then break; fi
                  sleep 0.2; i=${'$'}((i+1))
                done
                state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                if ! echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3'; then svc nfc enable 2>/dev/null || true; fi
                j=0
                while [ ${'$'}j -lt 60 ]; do
                  state=${'$'}(dumpsys nfc 2>/dev/null | grep -m1 -E 'mState=|state=' || true)
                  echo "${'$'}state" | grep -Eqi 'mState=on|state=on|STATE_ON|mState=3' && break
                  if [ ${'$'}((j % 10)) -eq 0 ]; then svc nfc enable 2>/dev/null || true; fi
                  sleep 0.25; j=${'$'}((j+1))
                done
                new=${'$'}(pidof com.android.nfc 2>/dev/null | awk '{print ${'$'}1}')
                echo "NEW_PID=${'$'}new"
                echo "NFC_STATE=${'$'}state"
            """.trimIndent()
            return rootShell.run(script, 35)
        }

        fun collectNfcConfigSnapshot(): String {
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
                echo "${'$'}files" | sed '/^$/d' | sort -u | while IFS= read -r f; do
                  [ -f "${'$'}f" ] || continue
                  echo
                  echo "===== FILE: ${'$'}f ====="
                  ls -lZ "${'$'}f" 2>/dev/null || ls -l "${'$'}f" 2>/dev/null || true
                  cat "${'$'}f" 2>/dev/null || echo '[read failed]'
                done
            """.trimIndent()
            return rootShell.run(script, 25, 400_000)
        }
    }
