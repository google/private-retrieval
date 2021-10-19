package com.google.private_retrieval.pir;

import com.google.common.logging.privateretrieval.PirLog.PirEvent;

/** Interface implemented by code interested in being notified of PIR events. */

public interface PirDownloadListener {
  public void onPirEvent(PirEvent pirEvent);

  public void onNumberOfChunksDetermined(int numberOfChunks);

  public void onFailure(PirUri pirUri, Exception exception);

  public void onSuccess(PirUri pirUri);
}
