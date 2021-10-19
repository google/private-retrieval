package com.google.private_retrieval.pir;

import static com.google.common.base.Strings.isNullOrEmpty;

import com.google.auto.value.AutoValue;
import java.util.Locale;
import org.checkerframework.checker.nullness.qual.Nullable;

/** The post-parsing components of a PIR uri. */
@AutoValue
public abstract class PirUri {
  public static final String PIR_URI_SCHEME = "pir";
  public static final int DEFAULT_PIR_PORT = 443;

  public abstract @Nullable String unparsedUri();

  public abstract String scheme();

  public abstract String host();

  public abstract int port();

  public abstract String databaseName();

  public abstract String databaseVersion();

  public abstract int entry();

  /** Returns null if the uri is valid, or an error message if invalid. */
  private @Nullable String validate() {
    switch (scheme()) {
      case PIR_URI_SCHEME:
        // Accepting PIR scheme.
        break;
      default:
        return "Not a valid PIR scheme.";
    }

    if (isNullOrEmpty(host())) {
      return "PIR Uri lacked valid host";
    }

    if (port() < 1 || port() > 65535) {
      return "PIR Uri lacked valid port";
    }

    if (isNullOrEmpty(databaseName())) {
      return "PIR Uri had empty database name";
    }

    if (isNullOrEmpty(databaseVersion())) {
      return "PIR Uri had empty database version";
    }

    if (entry() < 0) {
      return "PIR Uri had negative entry index";
    }

    return null;
  }

  public static PirUri create(
      @Nullable String unparsedUri,
      String scheme,
      String host,
      int port,
      String databaseName,
      String databaseVersion,
      int entry) {
    PirUri pirUri =
        createUnvalidated(unparsedUri, scheme, host, port, databaseName, databaseVersion, entry);
    String validationError = pirUri.validate();
    if (validationError != null) {
      throw new IllegalArgumentException(validationError);
    }
    return pirUri;
  }

  public static boolean isValid(
      @Nullable String unparsedUri,
      String scheme,
      String host,
      int port,
      String databaseName,
      String databaseVersion,
      int entry) {
    return null
        == getValidationError(
            unparsedUri, scheme, host, port, databaseName, databaseVersion, entry);
  }

  public static @Nullable String getValidationError(
      @Nullable String unparsedUri,
      String scheme,
      String host,
      int port,
      String databaseName,
      String databaseVersion,
      int entry) {
    return createUnvalidated(unparsedUri, scheme, host, port, databaseName, databaseVersion, entry)
        .validate();
  }

  private static PirUri createUnvalidated(
      @Nullable String unparsedUri,
      String scheme,
      String host,
      int port,
      String databaseName,
      String databaseVersion,
      int entry) {
    return new AutoValue_PirUri(
        unparsedUri,
        normalizeScheme(scheme),
        host,
        normalizePort(port),
        databaseName,
        databaseVersion,
        entry);
  }

  private static String normalizeScheme(String scheme) {
    return scheme.toLowerCase(Locale.US);
  }

  private static int normalizePort(int port) {
    return port < 0 ? DEFAULT_PIR_PORT : port;
  }
}
