package com.google.private_retrieval.pir;

import androidx.annotation.VisibleForTesting;
import com.google.privateinformationretrieval.v1.ClientToServerMessage;
import com.google.privateinformationretrieval.v1.ServerToClientMessage;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;

/** Allows injecting a mock bidirectional connection to the server for testing. */
@VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
public interface PirServerConnector {
  /**
   * Create a channel to the PIR server.
   *
   * @param pirUri contains scheme, host, and port information for connecting to the server.
   */
  public ManagedChannel createChannel(PirUri pirUri) throws PirDownloadException;

  /**
   * Create a bidirectional GRPC connection to the PIR server.
   *
   * @param channel a channel opened by {@link #createChannel(PirUri)}.
   * @param apiKey GRPC API Key.
   * @param serverToClientMessageHandler a {@link StreamObserver} for messages from the server, i.e.
   *     {@link StreamObserver#onNext(Object) result.onNext(message)} will be called on each message
   *     from the server.
   * @return a {@link StreamObserver} for messages from the server, each call to {@link
   *     StreamObserver#onNext(Object) result.onNext(message)} will send a message to the server.
   */
  public StreamObserver<ClientToServerMessage> createSession(
      ManagedChannel channel,
      String apiKey,
      StreamObserver<ServerToClientMessage> serverToClientMessageHandler)
      throws PirDownloadException;
}
