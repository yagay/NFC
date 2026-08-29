from pathlib import Path

p = Path('app/src/main/java/com/example/nfcdoorcard/MainActivity.kt')
s = p.read_text()

old_top = '''            TopAppBar(
                title = { Text("NFC Expert Pro 1.0.15") },
                actions = {
                    TextButton(onClick = {
                        if (!diagnosticRunning) {
                            diagnosticRunning = true
                            saveDiagnosticWithoutSharing { diagnosticRunning = false }
                        }
                    }) { Text(if (diagnosticRunning) "保存中" else "导出") }
                    TextButton(onClick = { AppLogger.clear(); logText = "" }) { Text("清空") }
                }
            )'''
new_top = '''            TopAppBar(
                title = { Text("NFC Expert Pro 1.0.15") }
            )'''
if old_top not in s:
    raise SystemExit('top app bar block not found')
s = s.replace(old_top, new_top, 1)

needle = '''                    item {
                        Box(
                            Modifier.fillMaxWidth().height(340.dp).padding(6.dp)
                                .background(Color(0xFF050505), RoundedCornerShape(4.dp)).padding(6.dp)
                        ) {
                            val lines = logText.split("\\n")
                            LazyColumn(state = logListState, modifier = Modifier.fillMaxSize()) {
                                items(lines) { line ->
                                    Text(
                                        line,
                                        color = when {
                                            line.contains("SUCCESS") || line.contains("APPLIED") || line.contains("ACCEPTED") || line.contains("READY") -> Color.Cyan
                                            line.contains("FAILED") || line.contains("ERROR") || line.contains("STALE") || line.contains("FATAL") -> Color.Red
                                            line.contains("WAITING") || line.contains("IDLE") -> Color.Yellow
                                            line.contains("RF") || line.contains("NFCID1") || line.contains("NfcUIDSim") || line.contains("VENDOR_BINDER") -> Color.Green
                                            else -> Color(0xFFD4D4D4)
                                        },
                                        fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            LaunchedEffect(selectedSource, lines.size) {
                                if (lines.isNotEmpty()) logListState.scrollToItem((lines.size - 1).coerceAtLeast(0))
                            }
                        }
                    }
'''
if needle not in s:
    raise SystemExit('log box block not found')
addition = needle + '''                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!diagnosticRunning) {
                                        diagnosticRunning = true
                                        saveDiagnosticWithoutSharing { diagnosticRunning = false }
                                    }
                                },
                                enabled = !diagnosticRunning,
                                modifier = Modifier.weight(1f)
                            ) { Text(if (diagnosticRunning) "保存中" else "导出日志") }
                            OutlinedButton(
                                onClick = { AppLogger.clear(); logText = "" },
                                modifier = Modifier.weight(1f)
                            ) { Text("清空日志") }
                        }
                    }
'''
s = s.replace(needle, addition, 1)
p.write_text(s)
