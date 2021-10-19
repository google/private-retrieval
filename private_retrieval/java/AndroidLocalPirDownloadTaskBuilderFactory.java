package com.google.private_retrieval.pir;

import com.google.common.base.Preconditions;
import com.google.common.base.Ticker;
import com.google.private_retrieval.pir.PirDownloadTask.Builder.PirDownloadTaskBuilderFactory;
import com.google.private_retrieval.pir.core.PirClientFactory;

/**
 * Factory for {@link PirDownloadTask.Builder}, backed by instances of {@link
 * AndroidLocalPirDownloadTask.Builder}.
 */
public class AndroidLocalPirDownloadTaskBuilderFactory implements PirDownloadTaskBuilderFactory {
  private static final PirServerConnector pirServerConnector =
      GrpcPirServerConnectorHolder.INSTANCE;
  private final Ticker ticker;
  private final PirClientFactory pirClientFactory;

  public AndroidLocalPirDownloadTaskBuilderFactory(
      Ticker ticker, PirClientFactory pirClientFactory) {
    this.ticker = Preconditions.checkNotNull(ticker);
    this.pirClientFactory = pirClientFactory;
  }

  @Override
  public AndroidLocalPirDownloadTask.Builder<?> create() {
    return AndroidLocalPirDownloadTask.builder()
        .setTicker(ticker)
        .setPirServerConnector(pirServerConnector)
        .setPirClientFactory(pirClientFactory);
  }
}
