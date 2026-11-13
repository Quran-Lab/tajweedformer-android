plugins {
    id("com.android.library") version "8.2.2" apply false
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    // screenshot tests of the sharing sheet on the JVM (the Compose module's unit tests only; nothing of it is published)
    id("app.cash.paparazzi") version "1.3.3" apply false
}
