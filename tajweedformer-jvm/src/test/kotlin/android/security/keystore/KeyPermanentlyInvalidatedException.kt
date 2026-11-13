package android.security.keystore

/**
 * A stand-in, on the desktop JVM, for Android's class of the same name: the JVM tests have no Android, and the SDK's
 * shared code tells this error by its class name ([ai.quranlab.tajweedformer.InstallKeyFailures]). Only the name and
 * its place under `InvalidKeyException` are Android's; nothing of the real class is here.
 */
class KeyPermanentlyInvalidatedException : java.security.InvalidKeyException("Key permanently invalidated")
