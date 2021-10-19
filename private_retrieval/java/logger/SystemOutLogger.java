package com.google.android.libraries.base;

import com.google.errorprone.annotations.FormatMethod;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import javax.annotation.Nullable;

/** Log directly to System.out. */
public class SystemOutLogger extends Logger {
  /** Creates a logger using given tag. */
  public static Logger create(String tag) {
    return new SystemOutLogger(tag);
  }

  protected SystemOutLogger(String tag) {
    super(tag);
  }

  @Override
  public Logger withTag(String tag) {
    return new SystemOutLogger(tag);
  }

  private static String getStackTraceString(Throwable t) {
    if (t != null) {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      return sw.toString();
    } else {
      return "";
    }
  }

  @Override
  @FormatMethod
  public void printLog(
      Level level, String tag, @Nullable Throwable t, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    if (t != null) {
      message = message + '\n' + getStackTraceString(t);
    }
    System.out.printf("[%s] %s: %s%n", level, tag, message);
  }
}
