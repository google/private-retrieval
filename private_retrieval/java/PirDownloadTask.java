package com.google.private_retrieval.pir;

import com.google.android.libraries.base.Logger;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a single PIR download. run() method starts the download task and block until the
 * download is either complete or has failed.
 */
public abstract class PirDownloadTask implements Runnable {
  protected final @Nullable PirDownloadConstraints downloadConstraints;
  protected final String apiKey;
  protected final PirUri pirUri;
  protected final ResponseWriter responseWriter;
  protected final PirDownloadListener pirDownloadListener;
  protected final long taskId;
  protected final int numChunksPerRequest;
  protected final Logger logger;

  protected PirDownloadTask(Builder<?> builder) {
    this.pirUri = Preconditions.checkNotNull(builder.pirUri);
    this.responseWriter = Preconditions.checkNotNull(builder.responseWriter);
    this.apiKey = Preconditions.checkNotNull(builder.apiKey);
    this.pirDownloadListener = Preconditions.checkNotNull(builder.pirDownloadListener);
    this.taskId = builder.taskId;
    this.numChunksPerRequest = builder.numChunksPerRequest;
    this.logger = Preconditions.checkNotNull(builder.logger);
    this.downloadConstraints = builder.downloadConstraints;
  }

  /**
   * Cancel the current task as soon as is feasible.
   *
   * <p>Note that the next feasible opportunity may not be until the task has started executing via
   * run().
   */
  public abstract void cancel();

  /** Check if the task has already been cancelled. */
  public abstract boolean hasBeenCancelled();

  /** Get any {@link PirDownloadConstraints} associated with this task. */
  public abstract @Nullable PirDownloadConstraints getDownloadConstraints();

  /** Get the {@link Future} associated with this task. */
  public abstract ListenableFuture<PirDownloadResult> getFuture();

  /** Builder for {@code PirDownloadTask} */
  public abstract static class Builder<T extends Builder<T>> {
    @Nullable String apiKey;
    @Nullable PirUri pirUri;
    @Nullable ResponseWriter responseWriter;
    PirDownloadListener pirDownloadListener = NoopPirDownloadListenerHolder.INSTANCE;
    long taskId = 0;
    int numChunksPerRequest = 1;
    Logger logger = SystemOutLoggerHolder.INSTANCE;
    @Nullable PirDownloadConstraints downloadConstraints;

    protected abstract T self();

    public abstract PirDownloadTask build() throws PirDownloadException;

    public T setApiKey(String apiKey) {
      this.apiKey = apiKey;
      return self();
    }

    public T setPirUri(PirUri pirUri) {
      this.pirUri = pirUri;
      return self();
    }

    public T setResponseWriter(ResponseWriter responseWriter) {
      this.responseWriter = responseWriter;
      return self();
    }

    public T setPirDownloadListener(PirDownloadListener pirDownloadListener) {
      this.pirDownloadListener = pirDownloadListener;
      return self();
    }

    public T setTaskId(long taskId) {
      this.taskId = taskId;
      return self();
    }

    public T setNumChunksPerRequest(int numChunksPerRequest) {
      this.numChunksPerRequest = numChunksPerRequest;
      return self();
    }

    public T setLogger(Logger logger) {
      this.logger = logger;
      return self();
    }

    public T setDownloadConstraints(PirDownloadConstraints downloadConstraints) {
      this.downloadConstraints = downloadConstraints;
      return self();
    }

    /**
     * Allows tests to either inject fake {@link PirDownloadTask}s or preconfigure {@link
     * PirDownloadTask}s with test-specific values.
     */
    public interface PirDownloadTaskBuilderFactory {
      Builder<?> create();
    }
  }
}
