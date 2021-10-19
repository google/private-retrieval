package com.google.private_retrieval.pir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Default implementation of {@link ResponseWriter} that doesn't do anything fancy. It simply writes
 * the output to a given file, passed into the constructor.
 */
public class FileResponseWriter implements ResponseWriter {
  private final File outputFile;

  /**
   * Constructs a {@link FileResponseWriter} that writes the download response to the given {@code
   * outputFile}.
   */
  public FileResponseWriter(File outputFile) {
    this.outputFile = outputFile;
  }

  @Override
  public void writeResponse(
      byte[] data, long downloadOffsetBytes, int dataBufferOffsetBytes, int countBytes)
      throws IOException {
    File outputDirectory = outputFile.getParentFile();
    if (outputDirectory != null && !outputDirectory.exists() && !outputDirectory.mkdirs()) {
      throw new IOException("Error creating output directory");
    }

    boolean isAppend = downloadOffsetBytes > 0;
    if (isAppend && downloadOffsetBytes != outputFile.length()) {
      throw new IOException(
          "Given offsetBytes does not correspond with existing data: "
              + downloadOffsetBytes
              + ", "
              + outputFile.length());
    }

    try (FileOutputStream outputStream = new FileOutputStream(outputFile, isAppend)) {
      outputStream.write(data, dataBufferOffsetBytes, countBytes);
    }
  }

  @Override
  public void deleteOutput() throws IOException {
    if (!outputFile.exists()) {
      // Nothing to be done.
      return;
    }
    if (!outputFile.delete()) {
      throw new IOException("Failed to delete output file.");
    }
  }

  @Override
  public long getNumExistingBytes() throws IOException {
    return outputFile.length();  // returns 0 if the file doesn't exist.
  }
}
