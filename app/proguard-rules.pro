# LSPosed loads the module entry from META-INF/xposed/java_init.list by class name.
-keep class com.example.nfcdoorcard.xposed.NfcInjectionModule { *; }

# Hook targets/profile serialization depend on stable member metadata and reflection.
-keep class com.example.nfcdoorcard.xposed.discovery.** { *; }
-keep class com.example.nfcdoorcard.xposed.profile.** { *; }
-keep class com.example.nfcdoorcard.xposed.payload.** { *; }

# Keep Android component entry points explicit for release verification.
-keep class com.example.nfcdoorcard.ConfigProvider { *; }
-keep class com.example.nfcdoorcard.MainActivity { *; }

# Preserve useful source/member names in crash diagnostics while still shrinking the app.
-keepattributes SourceFile,LineNumberTable,InnerClasses,EnclosingMethod,Signature
