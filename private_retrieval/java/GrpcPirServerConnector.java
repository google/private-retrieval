package com.google.private_retrieval.pir;

import io.grpc.ManagedChannelBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;

/**
 * Connects to live PIR server via GRPC.
 *
 * <p>This is the default implementation of {@link PirServerConnector}.
 */
public final class GrpcPirServerConnector extends AbstractGrpcPirServerConnector {
  @Override
  protected ManagedChannelBuilder<?> createChannelBuilder(PirUri pirUri) {
    return OkHttpChannelBuilder.forAddress(pirUri.host(), pirUri.port());
  }
}
