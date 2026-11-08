# The JNI library looks these up by name.
-keep class ai.quranlab.tajweedformer.engine.TajweedJni { native <methods>; }
-keep class ai.quranlab.tajweedformer.RecognizerException$EngineFailed { <init>(java.lang.String); }
-keep class ai.quranlab.tajweedformer.RecognizerException$ModelLoadFailed { <init>(java.lang.String); }
# The self-test clip is read as a Java resource.
-keepdirectories ai/quranlab/tajweedformer
# LiteRT is optional (the NPU backend): absent classes are fine.
-dontwarn com.google.ai.edge.litert.**
