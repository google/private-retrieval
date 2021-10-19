package com.google.private_retrieval.pir;

import com.google.common.logging.privateretrieval.PirLog.PirDownloadTaskEvent;
import com.google.private_retrieval.pir.core.JniLoader;

/**
 * Represents a single PIR download, running in a thread within the {@link PirDownloadProtocol}'s
 * executor.
 *
 * <p>This implementation is a thin extension of {@link LocalPirDownloadTask} which loads the
 * required native library upon construction on Android devices.
 */
public class AndroidLocalPirDownloadTask extends LocalPirDownloadTask {
  private AndroidLocalPirDownloadTask(Builder<?> builder) throws PirDownloadException {
    super(builder);

    if (!JniLoader.maybeInitialize(logger)) {
      throw PirDownloadException.builder()
          .setMessage("Could not initialize PIR library.")
          .setIsPermanent(true)
          .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.INTERNAL_ERROR)
          .build();
    }
  }

  public static Builder<?> builder() {
    return new AndroidLocalBuilder();
  }

  /**
   * Builder for this specific implementation, on top of the base class {@link
   * LocalPirDownloadTask.Builder}.
   */
  public abstract static class Builder<T extends Builder<T>>
      extends LocalPirDownloadTask.Builder<T> {
    @Override
    public PirDownloadTask build() throws PirDownloadException {
      return new AndroidLocalPirDownloadTask(this);
    }
  }

  private static class AndroidLocalBuilder extends Builder<AndroidLocalBuilder> {
    @Override
    protected AndroidLocalBuilder self() {
      return this;
    }
  }
}
