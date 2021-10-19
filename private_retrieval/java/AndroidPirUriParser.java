package com.google.private_retrieval.pir;

import android.net.Uri;
import com.google.common.base.Preconditions;
import org.checkerframework.checker.nullness.qual.Nullable;

/** PirUriParser using android.net.Uri. */
public final class AndroidPirUriParser implements PirUriParser {
  @Override
  public PirUri parse(String uri) {
    Preconditions.checkNotNull(uri);
    try {
      Uri parsed = Uri.parse(uri);
      String scheme = Preconditions.checkNotNull(parsed.getScheme());
      String host = Preconditions.checkNotNull(parsed.getHost());
      int port = parsed.getPort(); // -1 if not present
      String uriPath = Preconditions.checkNotNull(parsed.getPath());
      if (!uriPath.startsWith("/")) {
        throw new IllegalArgumentException("PIR Uri path did not start with /");
      }
      String[] uriPathElements = uriPath.substring(1).split("/", 4);
      if (uriPathElements.length < 3) {
        throw new IllegalArgumentException("PIR Uri lacked valid database name/version/entry");
      }

      String databaseName = uriPathElements[0];
      String databaseVersion = uriPathElements[1];
      String entryString = uriPathElements[2];
      int entry;
      try {
        entry = Integer.parseInt(entryString);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("PIR Uri had non-integer entry index", e);
      }

      return PirUri.create(uri, scheme, host, port, databaseName, databaseVersion, entry);
    } catch (Exception e) {
      throw new IllegalArgumentException("Exception while attempting to parse URL", e);
    }
  }

  @Override
  public String getValidationError(@Nullable String uri) {
    if (uri == null) {
      return "Uri was null.";
    }

    try {
      Uri parsed = Uri.parse(uri);
      @Nullable String scheme = parsed.getScheme();
      if (scheme == null) {
        return "Uri parser reported a null scheme.";
      }

      @Nullable String host = parsed.getHost();
      if (host == null) {
        return "Uri parser reported a null host.";
      }

      int port = parsed.getPort(); // -1 if not present
      @Nullable String uriPath = parsed.getPath();
      if (uriPath == null) {
        return "Uri parser reported a null path.";
      }
      if (!uriPath.startsWith("/")) {
        return "PIR Uri path did not start with /";
      }
      String[] uriPathElements = uriPath.substring(1).split("/", 4);
      if (uriPathElements.length < 3) {
        return "PIR Uri lacked valid database name/version/entry";
      }

      String databaseName = uriPathElements[0];
      String databaseVersion = uriPathElements[1];
      String entryString = uriPathElements[2];
      int entry;
      try {
        entry = Integer.parseInt(entryString);
      } catch (NumberFormatException e) {
        return "PIR Uri had non-integer entry index";
      }

      return PirUri.getValidationError(
          uri,
          Preconditions.checkNotNull(scheme),
          Preconditions.checkNotNull(host),
          port,
          Preconditions.checkNotNull(databaseName),
          Preconditions.checkNotNull(databaseVersion),
          entry);
    } catch (Exception e) {
      return "Exception while attempting to parse URL";
    }
  }

  @Override
  public boolean isValid(@Nullable String uri) {
    return null == getValidationError(uri);
  }
}
