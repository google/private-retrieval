package com.google.private_retrieval.pir;

/** Holder of a singleton {@link NoopPirDownloadListener} instance. */
public final class NoopPirDownloadListenerHolder {
  private NoopPirDownloadListenerHolder() {}

  public static final NoopPirDownloadListener INSTANCE = new NoopPirDownloadListener();
}
