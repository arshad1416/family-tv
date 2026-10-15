# Gson models are read/written reflectively.
-keep class app.familytv.portal.** { *; }
-keep class app.familytv.trakt.model.** { *; }
-keepattributes Signature, *Annotation*
# Keep class and method names, so crash reports sent to the portal can be read without a mapping file.
-dontobfuscate
-keepattributes SourceFile, LineNumberTable
