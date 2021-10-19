package com.google.private_retrieval.pir;

import com.google.common.base.Preconditions;
import com.google.privacy.privateretrieval.DatabaseParams;
import com.google.privacy.privateretrieval.PirParams;
import com.google.privacy.privateretrieval.RingLweParams;
import com.google.privacy.privateretrieval.XpirParams;

/** Utilities to assist with the use of PirClient. */
public class PirUtil {
  // RLWE parameters for a database up to 128 entries of any length.
  public static final long MODULUS1_PIR128 = 4611686018427322369L;
  public static final long MODULUS2_PIR128 = 18320723969L;
  public static final int LOGT_PIR128 = 18;
  public static final int LOGN_PIR128 = 11;
  public static final int VARIANCE_PIR128 = 8;
  public static final int RECURSION_LEVEL_PIR128 = 1;
  // RLWE parameters for a database up to 256 entries of any length.
  public static final long MODULUS1_PIR256 = 2305843009213616129L;
  public static final long MODULUS2_PIR256 = 9797890049L;
  public static final int LOGT_PIR256 = 17;
  public static final int LOGN_PIR256 = 11;
  public static final int VARIANCE_PIR256 = 8;
  public static final int RECURSION_LEVEL_PIR256 = 1;

  // Functions to compute PIR chunk sizes in bytes and the number of chunks.
  /**
   * Computes the number of plaintext bytes that can be stored in a single chunk given the number of
   * coefficients in the polynomial and the plaintext coefficient size.
   *
   * @param numCoeffs Number of coefficients in the polynomial.
   * @param logT Number of bytes stored in each coefficient of the plaintext polynomial.
   */
  private static int bytesPerPirChunk(int numCoeffs, int logT) {
    return (numCoeffs * logT) / 8;
  }

  /**
   * Computes the number of chunks that each entry needs to be split into.
   *
   * @param bytesPerChunk Number of bytes stored in a single chunk.
   * @param bytesPerEntry Number of bytes stored in each database entry.
   */
  private static int numberOfPirChunks(int bytesPerChunk, int bytesPerEntry) {
    return (bytesPerEntry + (bytesPerChunk - 1)) / bytesPerChunk;
  }

  /**
   * Configuration Parameters for an XPIR-style scheme with underlying RLWE encryption for a PIR
   * database of size up to 128 elements.
   *
   * @param databaseSize Number of entries in the database.
   * @param entrySize Number of bytes in each database entry. All entries must be the same size.
   * @param levelsOfRecursion Number of levels of recursion to use.
   */
  public static ConfigurationParameters getConfigurationParameters_PIR128(
      int databaseSize, int entrySize, int levelsOfRecursion) {
    RingLweParams ringLweParams =
        RingLweParams.newBuilder()
            .setInt64Modulus(
                RingLweParams.Int64Modulus.newBuilder()
                    .addModulus(MODULUS1_PIR128)
                    .addModulus(MODULUS2_PIR128)
                    .build())
            .setLogDegree(LOGN_PIR128)
            .setLogT(LOGT_PIR128)
            .setVariance(VARIANCE_PIR128)
            .build();

    PirParams pirParams =
        PirParams.newBuilder().setLevelsOfRecursion(RECURSION_LEVEL_PIR128).build();

    XpirParams xpirParams =
        XpirParams.newBuilder().setPirParams(pirParams).setRingLweParams(ringLweParams).build();

    DatabaseParams databaseParams =
        DatabaseParams.newBuilder().setEntrySize(entrySize).setDatabaseSize(databaseSize).build();

    ConfigurationParameters configParams =
        ConfigurationParameters.newBuilder()
            .setXpirParams(xpirParams)
            .setDatabaseParams(databaseParams)
            .build();

    return configParams;
  }

  /**
   * Configuration Parameters for an XPIR-style scheme with underlying RLWE encryption for a PIR
   * database of size up to 256 elements.
   *
   * @param databaseSize Number of entries in the database.
   * @param entrySize Number of bytes in each database entry. All entries must be the same size.
   * @param levelsOfRecursion Number of levels of recursion to use.
   */
  public static ConfigurationParameters getConfigurationParameters_PIR256(
      int databaseSize, int entrySize, int levelsOfRecursion) {
    RingLweParams ringLweParams =
        RingLweParams.newBuilder()
            .setInt64Modulus(
                RingLweParams.Int64Modulus.newBuilder()
                    .addModulus(MODULUS1_PIR256)
                    .addModulus(MODULUS2_PIR256)
                    .build())
            .setLogDegree(LOGN_PIR256)
            .setLogT(LOGT_PIR256)
            .setVariance(VARIANCE_PIR256)
            .build();

    PirParams pirParams =
        PirParams.newBuilder().setLevelsOfRecursion(RECURSION_LEVEL_PIR256).build();

    XpirParams xpirParams =
        XpirParams.newBuilder().setPirParams(pirParams).setRingLweParams(ringLweParams).build();

    DatabaseParams databaseParams =
        DatabaseParams.newBuilder().setEntrySize(entrySize).setDatabaseSize(databaseSize).build();

    ConfigurationParameters configParams =
        ConfigurationParameters.newBuilder()
            .setXpirParams(xpirParams)
            .setDatabaseParams(databaseParams)
            .build();

    return configParams;
  }

  /** Returns the number of database entries, as specified by the Configuration Parameters. */
  public static int getNumberOfEntries(ConfigurationParameters configurationParameters) {
    return configurationParameters.getDatabaseParams().getDatabaseSize();
  }

  /**
   * Returns the number of chunks each database entry will be divided into, as specified by the
   * Configuration Parameters.
   */
  public static int getNumberOfChunks(ConfigurationParameters configurationParameters) {
    switch (configurationParameters.getSchemeParamsCase()) {
      case XPIR_PARAMS:
        int degreeBound =
            1 << configurationParameters.getXpirParams().getRingLweParams().getLogDegree();
        int bytesPerChunk =
            bytesPerPirChunk(
                degreeBound, configurationParameters.getXpirParams().getRingLweParams().getLogT());
        return numberOfPirChunks(
            bytesPerChunk, configurationParameters.getDatabaseParams().getEntrySize());
      default:
        throw new IllegalArgumentException("Unrecognized PIR configuration scheme.");
    }
  }

  /**
   * Returns the size of each chunk of each database item (in bytes), as specified by the
   * Configuration Parameters.
   */
  public static long getChunkSizeBytes(ConfigurationParameters configurationParameters) {
    switch (configurationParameters.getSchemeParamsCase()) {
      case XPIR_PARAMS:
        int degreeBound =
            1 << configurationParameters.getXpirParams().getRingLweParams().getLogDegree();
        return bytesPerPirChunk(
            degreeBound, configurationParameters.getXpirParams().getRingLweParams().getLogT());
      default:
        throw new IllegalArgumentException("Unrecognized PIR configuration scheme.");
    }
  }

  /**
   * Returns the size of each database item (in bytes), as specified by the Configuration
   * Parameters.
   */
  public static long getEntrySizeBytes(ConfigurationParameters configurationParameters) {
    return configurationParameters.getDatabaseParams().getEntrySize();
  }

  /**
   * Returns the index of the chunk starting at the specified byte offset.
   *
   * @throws IllegalArgumentException when the byte offset is out of range, or if it doesn't lie on
   *     a chunk boundary.
   */
  public static long getChunkIndex(
      ConfigurationParameters configurationParameters, long byteOffset) {
    long chunkSize = getChunkSizeBytes(configurationParameters);
    long entrySize = getEntrySizeBytes(configurationParameters);

    Preconditions.checkArgument(
        byteOffset % chunkSize == 0,
        "byteOffset %s is not a multiple of chunk size %s",
        byteOffset,
        chunkSize);
    Preconditions.checkArgument(
        byteOffset >= 0 && byteOffset < entrySize,
        "byteOffset %s must be at least 0 and less than %s",
        byteOffset,
        entrySize);

    return byteOffset / chunkSize;
  }

  /**
   * Returns the byte offset of the chunk at the specified index.
   *
   * @throws IllegalArgumentException when the chunk index is out of range.
   */
  public static long getByteOffset(
      ConfigurationParameters configurationParameters, long chunkIndex) {
    long numChunks = getNumberOfChunks(configurationParameters);

    Preconditions.checkArgument(
        chunkIndex >= 0 && chunkIndex < numChunks,
        "chunkIndex %smust be at least 0 and less than%s",
        chunkIndex,
        numChunks);

    return chunkIndex * getChunkSizeBytes(configurationParameters);
  }

  private PirUtil() {}
}
