package com.google.android.libraries.base;

import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Class to perform platform independent logging.
 *
 * <p>Use one of {@code AndroidLogger.create} or {@code FloggerLogger.create} to create an instance.
 */
public abstract class Logger {

  private final String tag;

  protected Logger(String tag) {
    this.tag = tag;
  }

  public abstract Logger withTag(String tag);

  public void logErr(String message) {
    printLog(Level.SEVERE, tag, null, message);
  }

  public void logErr(String message, Object... args) {
    printLog(Level.SEVERE, tag, null, message, args);
  }

  public void logErr(Throwable t, String message) {
    printLog(Level.SEVERE, tag, t, message);
  }

  public void logErr(Throwable t, String message, Object... args) {
    printLog(Level.SEVERE, tag, t, message, args);
  }

  public void logWarn(String message) {
    printLog(Level.WARNING, tag, null, message);
  }

  public void logWarn(String message, Object... args) {
    printLog(Level.WARNING, tag, null, message, args);
  }

  public void logWarn(Throwable t, String message) {
    printLog(Level.WARNING, tag, t, message);
  }

  public void logWarn(Throwable t, String message, Object... args) {
    printLog(Level.WARNING, tag, t, message, args);
  }

  public void logInfo(String message) {
    printLog(Level.INFO, tag, null, message);
  }

  public void logInfo(String message, Object... args) {
    printLog(Level.INFO, tag, null, message, args);
  }

  public void logInfo(Throwable t, String message) {
    printLog(Level.INFO, tag, t, message);
  }

  public void logInfo(Throwable t, String message, Object... args) {
    printLog(Level.INFO, tag, t, message, args);
  }

  public void logDebug(String message) {
    printLog(Level.FINE, tag, null, message);
  }

  public void logDebug(String message, Object... args) {
    printLog(Level.FINE, tag, null, message, args);
  }

  public void logDebug(Throwable t, String message) {
    printLog(Level.FINE, tag, t, message);
  }

  public void logDebug(Throwable t, String message, Object... args) {
    printLog(Level.FINE, tag, t, message, args);
  }

  /** Issues a log message. */
  public abstract void printLog(
      Level level, String tag, @Nullable Throwable t, String message, Object... args);
}
