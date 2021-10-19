package com.google.private_retrieval.pir;

import com.google.common.logging.privateretrieval.PirLog.PirEvent;

/** A "do-nothing" PIR Download Listener. */
public class NoopPirDownloadListener implements PirDownloadListener {
  @Override
  public void onPirEvent(PirEvent pirEvent) {}

  @Override
  public void onNumberOfChunksDetermined(int numberOfChunks) {}

  @Override
  public void onFailure(PirUri pirUri, Exception exception) {}

  @Override
  public void onSuccess(PirUri pirUri) {}
}
