package com.google.private_retrieval.pir;

import com.google.auto.value.AutoValue;

/** Encapsulates the result of a PIR Download operation. */
@AutoValue
public abstract class PirDownloadResult {
  /** Returns an instance of a {@link PirDownloadResult} */
  public static PirDownloadResult create(PirUri source) {
    return new AutoValue_PirDownloadResult(source);
  }

  /** Returns the PirUri used to download the file. */
  public abstract PirUri source();

  @Override
  public final String toString() {
    PirUri source = source();
    return source == null ? "" : source.toString();
  }
}
