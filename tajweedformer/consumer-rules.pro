# The JNI library looks these up by name.
-keep class ai.quranlab.tajweedformer.engine.TajweedJni { native <methods>; }
-keep class ai.quranlab.tajweedformer.RecognizerException$EngineFailed { <init>(java.lang.String); }
-keep class ai.quranlab.tajweedformer.RecognizerException$ModelLoadFailed { <init>(java.lang.String); }
# The self-test clip is read as a Java resource.
-keepdirectories ai/quranlab/tajweedformer
# LiteRT is optional (the NPU backend): absent classes are fine.
-dontwarn com.google.ai.edge.litert.**
# Play Integrity is optional (attested sharing uploads; an app that wants it adds the dependency). These are the
# ten classes of it, and of the Play services tasks library it returns, that the SDK refers to. Without these
# lines R8 stops an app that shrinks and does not have the dependency with "Missing classes".
-dontwarn com.google.android.gms.tasks.Task
-dontwarn com.google.android.gms.tasks.Tasks
-dontwarn com.google.android.play.core.integrity.IntegrityManagerFactory
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$PrepareIntegrityTokenRequest
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$PrepareIntegrityTokenRequest$Builder
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityToken
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenProvider
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest
-dontwarn com.google.android.play.core.integrity.StandardIntegrityManager$StandardIntegrityTokenRequest$Builder
