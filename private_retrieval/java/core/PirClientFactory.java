package com.google.private_retrieval.pir.core;

import com.google.private_retrieval.pir.ConfigurationParameters;

/** Interface for a factory that creates PirClients. */
public interface PirClientFactory {
  public PirClient create(ConfigurationParameters configurationParameters);
}
