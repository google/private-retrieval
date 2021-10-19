package com.google.private_retrieval.pir;

import com.google.common.base.Preconditions;
import com.google.common.logging.privateretrieval.PirLog.PirDownloadTaskEvent;
import java.io.IOException;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A subclass of {@link DownloadException} for PIR errors. */
public class PirDownloadException extends IOException {
  private final boolean isCancellation;
  private final boolean isPermanent;
  private final PirDownloadTaskEvent.TaskCompleted.Failure.Code logsFailureCode;

  /** Buider for {@link PirDownloadException}. */
  public static class Builder {
    private boolean isCancellation;
    private boolean isPermanent;
    private @Nullable String message;
    private @Nullable Throwable cause;
    private PirDownloadTaskEvent.TaskCompleted.Failure.@Nullable Code logsFailureCode;

    Builder() {}

    public PirDownloadException build() {
      return new PirDownloadException(this);
    }

    public Builder withCause(Throwable cause) {
      this.cause = cause;
      return this;
    }

    public Builder setMessage(String message) {
      this.message = message;
      return this;
    }

    public Builder setMessage(String format, Object... args) {
      this.message = String.format(Locale.US, format, args);
      return this;
    }

    public Builder setIsCancellation(boolean isCancellation) {
      this.isCancellation = isCancellation;
      return this;
    }

    public Builder setIsPermanent(boolean isPermanent) {
      this.isPermanent = isPermanent;
      return this;
    }

    public Builder setLogsFailureCode(
        PirDownloadTaskEvent.TaskCompleted.Failure.Code logsFailureCode) {
      this.logsFailureCode = logsFailureCode;
      return this;
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  private PirDownloadException(Builder builder) {
    super(builder.message, builder.cause);
    isCancellation = builder.isCancellation;
    isPermanent = builder.isPermanent;
    logsFailureCode = Preconditions.checkNotNull(builder.logsFailureCode);
  }

  public boolean isCancellation() {
    return isCancellation;
  }

  public boolean isPermanent() {
    return isPermanent;
  }

  PirDownloadTaskEvent.TaskCompleted.Failure.Code getLogsFailureCode() {
    return logsFailureCode;
  }
}
