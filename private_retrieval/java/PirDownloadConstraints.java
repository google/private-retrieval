package com.google.private_retrieval.pir;

/** Constraints that must be satisfied for a PirDownloadTask to start or continue. */
public interface PirDownloadConstraints {
  /** Returns true if the PirDownloadtask may start/continue. */
  public boolean areSatisfied();

  /**
   * The PirDownloadConstraintsManager responsible for notifying whenever the value of
   * areSatisfied() may have changed.
   */
  public PirDownloadConstraintsManager getManager();
}
