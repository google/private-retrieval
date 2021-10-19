package com.google.private_retrieval.pir.core;

import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.base.Logger;
import javax.annotation.concurrent.GuardedBy;

/** Statically loads required JNI libraries for Android. */
public class JniLoader {
  private static class Initializer {
    @GuardedBy("this")
    private boolean wasInitializationAttempted = false;

    @GuardedBy("this")
    private boolean hasInitializationSucceeded = false;

    // Must be called at least once before any calls to JNI libraries loaded by this.
    synchronized boolean maybeInitialize(Logger logger) {
      if (!wasInitializationAttempted) {
        wasInitializationAttempted = true;
        try {
          logger.logDebug("Trying to load PIR native library.");
          System.loadLibrary("client_android_jni");
          hasInitializationSucceeded = true;
          logger.logDebug("PIR native library load succeeded.");
        } catch (SecurityException | UnsatisfiedLinkError | NullPointerException e) {
          hasInitializationSucceeded = false;
          logger.logErr(e, "PIR native library load failed.");
        }
      } else {
        logger.logDebug(
            "PIR native library load skipped; already attempted, was successful=%b",
            hasInitializationSucceeded);
      }
      return hasInitializationSucceeded;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    synchronized boolean getWasInitializationAttempted() {
      return wasInitializationAttempted;
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    synchronized void reset() {
      wasInitializationAttempted = false;
      hasInitializationSucceeded = false;
    }
  }

  private static final Initializer INITIALIZER_INSTANCE = new Initializer();

  public static boolean maybeInitialize(Logger logger) {
    return INITIALIZER_INSTANCE.maybeInitialize(logger);
  }

  @VisibleForTesting(otherwise = VisibleForTesting.NONE)
  public static void reset() {
    INITIALIZER_INSTANCE.reset();
  }

  @VisibleForTesting(otherwise = VisibleForTesting.NONE)
  public static boolean getWasInitializationAttempted() {
    return INITIALIZER_INSTANCE.getWasInitializationAttempted();
  }

  private JniLoader() {}
}
