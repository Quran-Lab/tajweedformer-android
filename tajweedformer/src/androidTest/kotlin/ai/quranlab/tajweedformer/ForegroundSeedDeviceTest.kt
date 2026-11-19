package ai.quranlab.tajweedformer

import ai.quranlab.tajweedformer.android.AndroidPlatform
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 3.1.5 issue the Flutter work found: the SDK learnt the activity in front only from lifecycle callbacks, so a
 * Client made after that activity had resumed (a Flutter app makes it then) had none: its sharing asks were counted
 * and never shown, and `listen()` was `microphone_denied` without a dialog. From 4.0 the activity is seeded when the
 * Client is made, from the context it is made with or, with the application's context (as Flutter passes it), from
 * the activity the system shows as resumed. No Client is made here (no key, no network): the platform's step is run.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundSeedDeviceTest {
    /** An activity of the test APK (its manifest, src/androidTest/AndroidManifest.xml). */
    class Front : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            last = this
        }

        companion object {
            @Volatile var last: Front? = null
        }
    }

    @Test
    fun theActivityInFrontIsSeededWhenTheClientIsMadeAfterItResumed() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val a = inst.startActivitySync(Intent(ctx, Front::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Front
        inst.waitForIdleSync()
        try {
            // the system's record of the resumed activity: what the seed falls back to with the application's context
            val found = AndroidPlatform.foundResumed()
            assertNotNull("the resumed activity is found without a callback", found)
            assertSame(a, found)
            // the Client made with the application's context (Flutter): the activity in front is known at once
            AndroidPlatform.seedForeground(ctx.applicationContext)
            assertSame(a, AndroidPlatform.resumedActivity)
        } finally {
            a.finish()
            inst.waitForIdleSync()
        }
    }
}
