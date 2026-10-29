# Gson models are read/written reflectively. Without a rule, R8 merges a class only Gson creates into another one
# and Gson throws (round 20: progress pulls, live scores, the move from Rust). GsonModelsKeptTest checks this list.
-keep class app.familytv.portal.** { *; }
-keep class app.familytv.trakt.model.** { *; }
-keep class app.familytv.user.Viewer { *; }
-keep class app.familytv.user.FavouriteEntity { *; }
-keep class app.familytv.user.SharedProgressPage { *; }
-keep class app.familytv.user.SharedProgressRow { *; }
-keep class app.familytv.scores.ScoreBoard { *; }
-keep class app.familytv.scores.ScoreGame { *; }
-keep class app.familytv.scores.ScoreTeam { *; }
-keep class app.familytv.transfer.Transfer$Local { *; }
-keep class app.familytv.transfer.Transfer$Favourite { *; }
-keep class app.familytv.transfer.Transfer$RustViewer { *; }
-keep class app.familytv.transfer.Transfer$Screensaver { *; }
-keep class app.familytv.usage.Usage$Event { *; }
-keep class app.familytv.trailer.TmdbTitle { *; }
-keep class app.familytv.ui.Featured$Lists { *; }
-keepattributes Signature, *Annotation*
# Keep class and method names, so crash reports sent to the portal can be read without a mapping file.
-dontobfuscate
-keepattributes SourceFile, LineNumberTable
