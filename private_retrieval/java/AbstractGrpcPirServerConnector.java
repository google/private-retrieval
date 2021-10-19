package com.google.private_retrieval.pir;

import com.google.privateinformationretrieval.v1.ClientToServerMessage;
import com.google.privateinformationretrieval.v1.PirServiceGrpc;
import com.google.privateinformationretrieval.v1.ServerToClientMessage;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Base class for connects to a PIR server via GRPC. */
public abstract class AbstractGrpcPirServerConnector implements PirServerConnector {
  private static final int CHANNEL_MAX_MESSAGE_SIZE = 16 * 1024 * 1024;
  private static final int KEEP_ALIVE_TIME_SECONDS = 60;

  private static final String API_KEY_HEADER = "X-Goog-Api-Key";

  @Override
  public ManagedChannel createChannel(PirUri pirUri) throws PirDownloadException {
    ManagedChannelBuilder<?> channelBuilder;
    switch (pirUri.scheme()) {
      case PirUri.PIR_URI_SCHEME:
        channelBuilder = createChannelBuilder(pirUri);
        break;
      default:
        throw PirDownloadException.builder()
            .setMessage("Unsupported PIR scheme: " + pirUri.scheme())
            .setIsPermanent(true)
            .build();
    }

    return channelBuilder
        .maxInboundMessageSize(CHANNEL_MAX_MESSAGE_SIZE)
        .keepAliveTime(KEEP_ALIVE_TIME_SECONDS, TimeUnit.SECONDS)
        .build();
  }

  protected abstract ManagedChannelBuilder<?> createChannelBuilder(PirUri pirUri);

  @Override
  public StreamObserver<ClientToServerMessage> createSession(
      ManagedChannel channel,
      String apiKey,
      StreamObserver<ServerToClientMessage> serverToClientMessageHandler) {
    List<ClientInterceptor> interceptors = new ArrayList<>();
    Metadata extraHeaders = new Metadata();
    extraHeaders.put(Metadata.Key.of(API_KEY_HEADER, Metadata.ASCII_STRING_MARSHALLER), apiKey);
    interceptors.add(MetadataUtils.newAttachHeadersInterceptor(extraHeaders));

    PirServiceGrpc.PirServiceStub stub =
        PirServiceGrpc.newStub(ClientInterceptors.intercept(channel, interceptors));
    StreamObserver<ClientToServerMessage> session = stub.session(serverToClientMessageHandler);
    return session;
  }
}
