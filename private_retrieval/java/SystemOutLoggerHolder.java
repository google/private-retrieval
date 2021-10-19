package com.google.private_retrieval.pir;

import com.google.android.libraries.base.Logger;
import com.google.android.libraries.base.SystemOutLogger;

/** Holder of a singleton {@link SystemOutLogger} instance. */
final class SystemOutLoggerHolder {
  private SystemOutLoggerHolder() {}

  static final Logger INSTANCE = SystemOutLogger.create("PIR");
}
