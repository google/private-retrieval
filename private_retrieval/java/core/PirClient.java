package com.google.private_retrieval.pir.core;

import com.google.private_retrieval.pir.PirRequest;
import com.google.private_retrieval.pir.PirResponseChunk;
import com.google.private_retrieval.pir.SerializedClientSession;

/** Interface representing a client for a Private Information Retrieval protocol. */
public interface PirClient {

  /* Begins a session, setting the database entry desired by this client, and performing associated
   * setup. A subsequent call to CreateRequest will create a PIR request for this session.
   *
   * If a session is already underway when BeginSession is called, that session will be abandoned
   * and replaced with a new session.
   *
   * Throws a RuntimeException if the index is not in the range of the database, as specified by
   * this client's ConfigurationParameters.
   */
  public void beginSession(long entryIndex);

  /* Serializes the client session, together with any state related to the selected entry. A client
   * session can be restored from the serialized state.
   *
   * Throws a RuntimeException if BeginSession or RestoreSession has not been called before calling
   * this.
   */
  public SerializedClientSession serializeSession();

  /* Restores the serialized client session, including the selected entry.
   *
   * If a session is already underway when RestoreSession is called, that session will be abandoned
   * and replaced with the deserialized session.
   *
   * Throws a RuntimeException if the supplied SerializedClientSession is not consistent with
   * this client's ConfigurationParameters.
   */
  public void restoreSession(SerializedClientSession serialized);

  /* Creates a query vector for the desired index, stored in the returned PirRequest.
   *
   * Throws a RuntimeException if BeginSession or RestoreSession has not been called before
   * calling this.
   */
  public PirRequest createRequest();

  /* Decrypts and processes a single PirResponseChunk. Expects the chunk to be consistent with
   * ConfigurationParameters, and also consistent with any session state this client has related to
   * its selected index.
   *
   * Throws a RuntimeException if BeginSession or RestoreSession has not been called before
   * calling this.
   * Throws a RuntimeException if the response chunk is inconsistent with ConfigurationParameters or
   * with client's session state.
   */
  public byte[] processResponseChunk(PirResponseChunk responseChunk);
}
;
