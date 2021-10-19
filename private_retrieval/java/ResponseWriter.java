package com.google.private_retrieval.pir;

import java.io.IOException;

/**
 * An interface for handling the response of a download. Implementations could write the response to
 * disk or do other processing as required, e.g., compressing the data or saving hashes for
 * integrity.
 */
public interface ResponseWriter {
  /**
   * Writes {@code response} out to some storage, possibly doing other processing. If {@code
   * downloadOffsetBytes} is greater than 0, then this is a resumed download: the first byte to be
   * written is offset {@code downloadOffsetBytes} into the overall download. In this case, the data
   * should be appended to any existing data. Otherwise any existing data should be replaced. A
   * non-zero value of {@code downloadOffsetBytes} will only be passed if that value has previously
   * been returned by {@link #getNumExistingBytes()}. This restriction ensures that we don't
   * accidentally skip over bytes in the download.
   *
   * @param data the data to be written.
   * @param downloadOffsetBytes indicates the offset that content is at in the overall download.
   * @param dataBufferOffsetBytes indcates the first byte from the data buffer to write.
   * @param countBytes the number of bytes from the buffer to write.
   */
  public void writeResponse(
      byte[] data, long downloadOffsetBytes, int dataBufferOffsetBytes, int countBytes)
      throws IOException;

  /**
   * Deletes the output if any exists. This method would be called, for example, if the file is
   * found to be corrupt, e.g due to a bad partial download. After a call to this method, an
   * immediately subsequent call to {@link #getNumExistingBytes()} should return 0.
   */
  public void deleteOutput() throws IOException;

  /**
   * Returns the number of bytes already read and saved from previous {@link #writeResponse()}
   * calls, potentially by an earlier instance of the implementation (e.g. if the process was killed
   * partway through handling a download). This allows for resuming a download by informing the
   * downloader how many bytes were previously saved by an earlier instance and thus from what start
   * offset a ranged download could resume (see {@code downloadOffsetBytes} in {@link
   * #writeResponse()}).
   *
   * @return the number of existing bytes already read and saved for this download
   */
  public long getNumExistingBytes() throws IOException;
}
