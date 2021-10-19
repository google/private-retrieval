package com.google.private_retrieval.pir.core;

import com.google.private_retrieval.pir.ConfigurationParameters;

/** A PirClientFactory that creates PirClientSwig objects. */
public class PirClientSwigFactory implements PirClientFactory {
  public PirClientSwigFactory() {};

  @Override
  public PirClient create(ConfigurationParameters configurationParameters) {
    return PirClientSwig.create(configurationParameters);
}
}
