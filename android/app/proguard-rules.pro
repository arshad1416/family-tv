# Gson models are read/written reflectively.
-keep class app.familytv.portal.** { *; }
-keep class app.familytv.trakt.model.** { *; }
-keep class app.familytv.user.Viewer { *; }
-keep class app.familytv.user.FavouriteEntity { *; }
-keep class app.familytv.usage.Usage$Event { *; }
-keep class app.familytv.trailer.TmdbTitle { *; }
-keep class app.familytv.ui.Featured$Lists { *; }
-keepattributes Signature, *Annotation*
# Keep class and method names, so crash reports sent to the portal can be read without a mapping file.
-dontobfuscate
-keepattributes SourceFile, LineNumberTable
