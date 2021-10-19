package com.google.private_retrieval.pir;

/** Holder of a singleton {@link GrpcPirServerConnector} instance. */
final class GrpcPirServerConnectorHolder {
  private GrpcPirServerConnectorHolder() {}

  static final GrpcPirServerConnector INSTANCE = new GrpcPirServerConnector();
}
