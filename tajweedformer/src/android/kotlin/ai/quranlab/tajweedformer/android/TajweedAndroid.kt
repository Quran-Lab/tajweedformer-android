package ai.quranlab.tajweedformer.android

import ai.quranlab.tajweedformer.RemoteTajweed
import ai.quranlab.tajweedformer.ResolvedTajweedSettings
import ai.quranlab.tajweedformer.TajweedModel
import ai.quranlab.tajweedformer.TajweedOptions
import ai.quranlab.tajweedformer.TajweedRecognizer
import ai.quranlab.tajweedformer.TajweedSettings
import android.content.Context
import android.content.pm.ApplicationInfo

/** Android glue for the tajweed settings. */
object TajweedAndroid {
    /**
     * Whether the HOST APP is a debug build (its `ApplicationInfo.FLAG_DEBUGGABLE`), the SDK's own
     * build type does not matter. Only then can app code turn on a check calibration.json marks
     * `off`; the portal never can.
     */
    @JvmStatic
    fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Recognizer options with [TajweedRecognizer.Options.debugBuild] taken from the host app. */
    @JvmStatic
    @JvmOverloads
    fun options(context: Context, tajweed: TajweedOptions = TajweedOptions(), sampleRate: Int = 16_000): TajweedRecognizer.Options =
        TajweedRecognizer.Options(tajweed = tajweed, debugBuild = isDebuggable(context), sampleRate = sampleRate)

    /** [TajweedSettings.resolve] with the debug flag of the host app. */
    @JvmStatic
    @JvmOverloads
    fun resolve(context: Context, model: TajweedModel, remoteJson: String?, app: TajweedOptions = TajweedOptions()): ResolvedTajweedSettings =
        TajweedSettings.resolve(model.calibration, RemoteTajweed.parseJson(remoteJson), app, isDebuggable(context))
}
