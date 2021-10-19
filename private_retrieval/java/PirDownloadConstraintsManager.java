package com.google.private_retrieval.pir;

/**
 * Responsible for notifying whenever the value of a PirDownloadConstraints.areSatisfied() may have
 * changed.
 */
public interface PirDownloadConstraintsManager {
  /** Interface for receiveing PirDownloadConstraints.areSatisfied() value notifications. */
  public interface Listener {
    /**
     * Called whenever the areSatisfied() value may have changed for any PirDownloadConstraints
     * associated with this manager.
     */
    public void onConstraintsMayHaveChanged();
  }

  /**
   * Begin invoking listener.onConstraintsMayHaveChanged() whenever the areSatisfied() value may
   * have changed for any PirDownloadConstraints associated with this manager.
   */
  public void registerConstraintsChangeListener(Listener listener);

  /**
   * Stop invoking a listener that was previously registered via registerConstraintsChangeListener.
   */
  public void unregisterConstraintsChangeListener(Listener listener);
}
