package com.google.private_retrieval.pir;

/** Interface for platform-specific PIR Uri parsers. */
public interface PirUriParser {
  public PirUri parse(String uri);

  public boolean isValid(String uri);

  public String getValidationError(String uri);
}
