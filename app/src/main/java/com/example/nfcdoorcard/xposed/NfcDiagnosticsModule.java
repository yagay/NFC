package com.example.nfcdoorcard.xposed;

/**
 * Stable LSPosed entry point.
 *
 * Keep this class name permanently so LSPosed static-scope entry caching does not
 * break across app updates. All production behavior lives in NfcInjectionModule.
 */
public class NfcDiagnosticsModule extends NfcInjectionModule {
}
