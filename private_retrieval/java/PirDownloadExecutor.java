package com.google.private_retrieval.pir;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An executor for PirDownloadTasks, allowing their execution to be deferred until the task's
 * download constraints are satisfied.
 *
 * <p>PirDownloadExecutor makes sure the task doesn't start until its constraints are satisfied.
 * Once the constraints are satisfied, the task will begin. If the constraints stop being satisfied,
 * the task will cancel itself. PirDownloadExecutor will *not* reschedule such tasks automatically;
 * this is the responsibility of the surrounding system.
 */
public class PirDownloadExecutor {
  // Each activePirDownloadConstraintsManager instance needs to register a set of listeners
  // when it is managing constraints for deferred PirDownloadTasks.  The contract here is
  // that the PirDownloadConstraintsManager has listeners registered when it is a key in this
  // map, and that it only has a key in this map when the associated DeferredTasks contains at least
  // one deferred PirDownloadtask.
  private final IdentityHashMap<PirDownloadConstraintsManager, DeferredTasks>
      deferredTasksByManager = new IdentityHashMap<>();

  // The executor upon which the PirDownloadTask will be run, once its DownloadContraints are met.
  private final Executor delegate;

  private final List<Listener> listeners = new ArrayList<>();

  /** Listener for changes to the executor's state. */
  public static interface Listener {
    public void onHasDeferredTasksChanged(boolean hasDeferredTasks);
  }

  public PirDownloadExecutor(Executor delegate) {
    this.delegate = delegate;
  }

  public void addListener(Listener listener) {
    listeners.add(listener);
  }

  /**
   * Container for all PirDownloadTasks associated with DownloadConstraints from the same
   * PirDownloadConstraintsManager, for which the DownloadConstraints have not yet been satisfied.
   */
  private class DeferredTasks implements PirDownloadConstraintsManager.Listener {
    // The PirDownloadConstraintsManager managing the download constraints for all tasks
    // in this container.
    final PirDownloadConstraintsManager manager;

    // Tasks that should be executed as soon as their download constraints are satisfied.
    final List<PirDownloadTask> tasks = new ArrayList<>();

    DeferredTasks(PirDownloadConstraintsManager manager) {
      this.manager = manager;
      manager.registerConstraintsChangeListener(this);
    }

    @Override
    public void onConstraintsMayHaveChanged() {
      // Somemthing about the constraints has changed.  Check whether any of the deferred tasks are
      // now ready to execute.  If so, remove them from the deferred list and schedule them for
      // execution.
      synchronized (deferredTasksByManager) {
        Iterator<PirDownloadTask> it = tasks.iterator();
        while (it.hasNext()) {
          PirDownloadTask task = it.next();
          if (isReadyToExecute(task)) {
            it.remove();
            delegate.execute(task);
          }
        }

        // If we've now handled all tasks deferred by this manager, unregister the manager's
        // listeners and get release this DeferredTasks group.
        if (tasks.isEmpty()) {
          manager.unregisterConstraintsChangeListener(this);
          deferredTasksByManager.remove(manager);
        }

        // If there are now no deferred tasks, notify all listeners that hasDeferredTasks has
        // changed.
        if (deferredTasksByManager.isEmpty()) {
          for (Listener listener : listeners) {
            listener.onHasDeferredTasksChanged(false);
          }
        }
      }
    }

    public void add(PirDownloadTask task) {
      synchronized (deferredTasksByManager) {
        tasks.add(task);
      }
    }
  }

  /**
   * Returns true if the task is ready to execute, either because it has its constraints satisfied
   * or because it has been cancelled (and so it should execute now to generate its cancellation
   * callback notification).
   */
  private static boolean isReadyToExecute(PirDownloadTask task) {
    if (task.hasBeenCancelled()) {
      return true;
    }
    @Nullable PirDownloadConstraints constraints = task.getDownloadConstraints();
    return (constraints == null || constraints.areSatisfied());
  }

  /**
   * Schedule the PirDownloadTask to run on the delegate Executor as soon as its DownloadConstraints
   * have been satisfied.
   */
  public void execute(PirDownloadTask task) {
    if (isReadyToExecute(task)) {
      delegate.execute(task);
    } else {
      synchronized (deferredTasksByManager) {
        boolean hadDeferredTasks = hasDeferredTasks();

        DeferredTasks deferredTasks;
        PirDownloadConstraintsManager manager = task.getDownloadConstraints().getManager();
        if (deferredTasksByManager.containsKey(manager)) {
          deferredTasks = deferredTasksByManager.get(manager);
        } else {
          deferredTasks = new DeferredTasks(manager);
          deferredTasksByManager.put(manager, deferredTasks);
        }
        deferredTasks.add(task);

        if (!hadDeferredTasks) {
          // If there were not previously deferred tasks, notify all listeners that hasDeferredTasks
          // has changed.
          for (Listener listener : listeners) {
            listener.onHasDeferredTasksChanged(true);
          }
        }
      }
    }
  }

  public boolean hasDeferredTasks() {
    synchronized (deferredTasksByManager) {
      return !deferredTasksByManager.isEmpty();
    }
  }
}
