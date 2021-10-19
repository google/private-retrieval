package com.google.android.libraries.base;

import android.util.Log;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Logger for Android. */
public final class AndroidLogger extends Logger {

  public static final String DEFAULT_PREFIX = "brella";

  private final String prefix;

  /** Creates a logger using given tag (and 'brella' as default prefix). */
  public static Logger create(String tag) {
    return new AndroidLogger(DEFAULT_PREFIX, tag);
  }

  /** Creates a logger using given prefix and tag. */
  public static Logger create(String prefix, String tag) {
    return new AndroidLogger(prefix, tag);
  }

  private AndroidLogger(String prefix, String tag) {
    super(tag);
    this.prefix = prefix;
  }

  @Override
  public Logger withTag(String tag) {
    return new AndroidLogger(DEFAULT_PREFIX, tag);
  }

  @Override
  public void printLog(
      Level level, String tag, @Nullable Throwable t, String message, Object... args) {
    int priority;
    if (level.equals(Level.SEVERE)) {
      priority = Log.ERROR;
    } else if (level.equals(Level.WARNING)) {
      priority = Log.WARN;
    } else if (level.equals(Level.INFO)) {
      priority = Log.INFO;
    } else if (level.equals(Level.FINE)) {
      priority = Log.DEBUG;
    } else {
      priority = Log.WARN;
    }
    if (args.length > 0) {
      message = String.format(message, args);
    }
    if (t != null) {
      message = message + '\n' + Log.getStackTraceString(t);
    }
    Log.println(priority, prefix + "." + tag, message);
  }
}
