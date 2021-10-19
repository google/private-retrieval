package com.google.private_retrieval.pir;

import com.google.android.libraries.base.Logger;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import javax.annotation.concurrent.GuardedBy;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A finite state machine.
 *
 * <p>This FSM can accept events asynchronously from any thread, but processes them in serial on a
 * single thread (the thread on which {@link #run() run()} is executed, herein referred to as the
 * event loop thread).
 */
public final class FiniteStateMachine<
        S extends FiniteStateMachine.State<S>, E extends FiniteStateMachine.Event<S>>
    implements Runnable {

  /** A State of the Finite State Machine. */
  public interface State<S extends State<S>> {
    /**
     * Return a short string description of this state.
     *
     * <p>Can be called from any thread.
     */
    String describe();

    /**
     * Called when the state machine transitions to this state.
     *
     * <p>Also called on the initial state when the state machine is {@link FiniteStateMachine#run()
     * run}, before any events are processed. Note that between the construction of the finite state
     * machine and the state machine being {@link FiniteStateMachine#run() run}, the state machine's
     * initialState won't have had this method called, but {@link FiniteStateMachine#describeState()
     * describeState} method will indicate that the initialState is the current state.
     *
     * <p>Can only be called from the event loop thread.
     *
     * @return the State the finite state machine should transition to as a result of onEnter, or
     *     {@code this} to remain in this State, or {@code null} to halt the state machine (i.e. to
     *     indicate that this is a terminal state).
     */
    @Nullable
    S onEnter() throws Exception;

    /**
     * Called just before the state machine transitions away from this state.
     *
     * <p>Can only be called from the event loop thread. This method will never be called for
     * terminal states and must not throw exceptions.
     */
    void onExit();

    /**
     * Called when an exception is thrown while executing {@link #onEnter()} or {@link
     * Event#dispatchTo(State)}.
     *
     * @return the State the finite state machine should transition to as a result of onEnter, or
     *     {@code this} to remain in this State.
     */
    S onException(Throwable t);
  }

  /** An Event that drives the Finite State Machine. */
  public interface Event<S extends State<S>> {
    /**
     * Asks {@code state} (the current state of the finite state machine) to handle this {@link
     * Event}.
     *
     * @return the {@link State} the finite state machine should transition to as a result of {@code
     *     dispatchTo}, or {@code this} to remain in {@link State} {@code state}.
     */
    @Nullable
    S dispatchTo(S state) throws Exception;
  }

  /** The current state. */
  @GuardedBy("this")
  private S state;

  /**
   * Signals whether there has been a request to terminate the event loop at the next opportunity.
   */
  @GuardedBy("this")
  private boolean terminated;

  /** Runnables queued for running when the event queue is next empty. */
  @GuardedBy("this")
  private final ArrayList<Runnable> onIdleRunnables = new ArrayList<>();

  /** Events queued to be handled by the state machine. */
  private final BlockingQueue<E> eventQueue = new LinkedBlockingQueue<E>();

  private final Logger logger;

  public FiniteStateMachine(Logger logger, S initialState) {
    this.logger = logger;
    this.state = initialState;
  }

  /**
   * Send an event into the Finite State Machine.
   *
   * <p>Can be called from any thread.
   */
  public synchronized void sendEvent(E event) {
    if (!terminated) {
      eventQueue.offer(event);
    }
  }

  /**
   * Return a short string description of the current state.
   *
   * <p>Can be called from any thread.
   */
  public synchronized String describeState() {
    return state.describe();
  }

  /**
   * State the state machine's event loop on the current thread.
   *
   * <p>This method won't exit until the state machine enters a terminal state.
   */
  @Override
  public void run() {
    // Enter the initial state
    runStateTransitionChain();

    // Start processing events
    runEventLoop();
  }

  /**
   * Trigger {@link State#onEnter()} logic for the current state.
   *
   * <p>This method should be called once each time {@code state} is changed. This method will call
   * {@link State#onEnter()} on the state. If {@link State#onEnter()} returns a desired next state
   * different from the current one, this method will transition to the new state and repeat the
   * {@link State#onEnter()} process.
   *
   * <p>This will only run on the event loop thread (i.e. on the where {@link #run()} is called.)
   */
  private void runStateTransitionChain() {
    while (true) {
      S currentState;
      synchronized (this) {
        if (terminated) {
          exitEventLoopNow();
          return;
        }

        currentState = state;
      }

      logger.logDebug("Entering state: %s", currentState.describe());
      S nextState;
      try {
        nextState = currentState.onEnter();
      } catch (Exception e) {
        nextState = currentState.onException(e);
      }

      synchronized (this) {
        if (terminated) {
          exitEventLoopNow();
          return;
        }
      }

      // If the next state is different from the current state, repeat the loop to enter the new
      // next state.
      if (nextState == currentState) {
        return;
      }

      if (nextState == null) {
        terminate();
        return;
      }

      // Exit the current state
      currentState.onExit();

      // Enter the new state (onEnter will be called on the next iteration of this loop.)
      synchronized (this) {
        state = nextState;
      }
    }
  }

  /**
   * Implements the event loop.
   *
   * <p>This will only run on the event loop thread (i.e. on the where {@link #run()} is called.)
   */
  private void runEventLoop() {
    while (true) {
      synchronized (this) {
        if (terminated) {
          exitEventLoopNow();
          return;
        }
      }

      // poll() returns null if the queue is currently empty.
      E event = eventQueue.poll();
      if (event == null) {
        onIdle();
        while (true) {
          try {
            // take() blocks until the queue is not empty.
            event = eventQueue.take();
            break;
          } catch (InterruptedException e) {
            synchronized (this) {
              if (terminated) {
                exitEventLoopNow();
                return;
              }
            }
            logger.logDebug("Interrupted waiting for event; retrying.");
          }
        }
      }

      S currentState;
      synchronized (this) {
        if (terminated) {
          exitEventLoopNow();
          return;
        }

        currentState = state;
      }

      S nextState;
      try {
        nextState =
            Preconditions.checkNotNull(Preconditions.checkNotNull(event).dispatchTo(currentState));
      } catch (Exception e) {
        nextState = currentState.onException(e);
      }

      synchronized (this) {
        if (terminated) {
          exitEventLoopNow();
          return;
        }
      }

      if (nextState == null) {
        terminate();
        return;
      } else if (nextState != currentState) {
        // Exit the current state
        currentState.onExit();

        // Enter the new state.
        synchronized (this) {
          state = nextState;
        }
        runStateTransitionChain();
      }
    }
  }

  private void exitEventLoopNow() {
    logger.logDebug("Exiting event loop.");
    eventQueue.clear();
    onIdle();
  }

  /**
   * Trigger runnables registered with {@link #runOnIdle(Runnable)}.
   *
   * <p>Can only be called from the event loop thread.
   */
  private synchronized void onIdle() {
    for (Runnable onIdleRunnable : onIdleRunnables) {
      onIdleRunnable.run();
    }
    onIdleRunnables.clear();
  }

  /**
   * Register {@link Runnable}s to be executed the next time the event queue is quiescent.
   *
   * <p>This method is intended to allow tests to send some events into the state machine and wait
   * for them to be fully processed before interrogating the state of the state machine.
   *
   * <p>There is no guarantee which thread the runnable will be executed on. If the state machine
   * has already terminated, the runnable will be executed on the caller's thread before the method
   * returns.
   *
   * <p>Can be called from any thread.
   */
  // @VisibleForTesting
  public synchronized void runOnIdle(Runnable runnable) {
    if (terminated) {
      runnable.run();
    } else {
      onIdleRunnables.add(runnable);
    }
  }

  /**
   * Terminate the state machine as soon as possible.
   *
   * <p>Can only be called from the event loop thread.
   */
  private synchronized void terminate() {
    if (terminated) {
      logger.logDebug("Event loop already terminated; ignoring terminate().");
      return;
    }
    logger.logDebug("Signaling to exit event loop.");
    terminated = true;
  }
}
