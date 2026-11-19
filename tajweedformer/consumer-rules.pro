# The JNI bridge to the Quran Lab Core registers the natives of CoreNative by name, and calls back into CoreHost (and
# the subclasses the SDK makes) by name and signature.
-keep class ai.quranlab.tajweedformer.core.CoreNative { native <methods>; }
-keep class ai.quranlab.tajweedformer.core.CoreHost { *; }
-keep class * extends ai.quranlab.tajweedformer.core.CoreHost { *; }
# The self-test clip is read as a Java resource.
-keepdirectories ai/quranlab/tajweedformer
# Play Integrity is a dependency of the AAR since 3.1.0; an app may still exclude it (the SDK then asks for no
# integrity token). These are the ten classes of it, and of the Play services tasks library it returns, that the SDK
# refers to. Without these lines R8 stops an app that shrinks and excluded the dependency with "Missing classes".
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
