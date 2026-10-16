# Gson models are read/written reflectively.
-keep class app.familytv.portal.** { *; }
-keep class app.familytv.trakt.model.** { *; }
-keepattributes Signature, *Annotation*
# Keep class and method names, so crash reports sent to the portal can be read without a mapping file.
-dontobfuscate
-keepattributes SourceFile, LineNumberTable
# Shared progress is decoded reflectively before external discovery verifies its profile.
-keep class app.familytv.user.SharedProgressPage { *; }
-keep class app.familytv.user.SharedProgressRow { *; }
# Profile key responses are decoded by Gson; release shrinking must retain their state.
-keep class app.familytv.discovery.ProfileRdKey { *; }
