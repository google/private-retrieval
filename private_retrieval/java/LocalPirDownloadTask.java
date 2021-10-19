package com.google.private_retrieval.pir;

import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.base.Logger;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.logging.privateretrieval.PirLog.PirDownloadTaskEvent;
import com.google.common.logging.privateretrieval.PirLog.PirEvent;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.private_retrieval.pir.core.PirClient;
import com.google.private_retrieval.pir.core.PirClientFactory;
import com.google.privateinformationretrieval.v1.ClientToServerMessage;
import com.google.privateinformationretrieval.v1.ServerToClientMessage;
import com.google.privateinformationretrieval.v1.ServerToClientMessage.GetChunksResponse;
import com.google.privateinformationretrieval.v1.ServerToClientMessage.SelectDatabaseResponse;
import com.google.privateinformationretrieval.v1.ServerToClientMessage.SelectFileResponse;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Represents a single PIR download, running in a thread within the {@link PirDownloadProtocol}'s
 * executor.
 *
 * <p>LocalPirDownloadTask is implemented as an explicit state machine (see {@link
 * FiniteStateMachine}).
 *
 * <p>The state machine itself only executes on the thread from which PirDownloadTask.run was called
 * (by the executor). Each state in the machine represented as a subclass of {@link BaseState
 * PirDownloadTask.BaseState}. All communication with the state machine occurs by way of explicit
 * events, represented as subclasses of {@link Event PirDownloadTask.Event}. Events can sent to the
 * state machine from any thread but those events are only consumed on the state machine's host
 * thread.
 *
 * <p>The state machine:
 *
 * <pre>
 *
 *    WaitingToRunState
 *           |
 *           | (Executor starts the finite state machine)
 *           V
 *    ConnectingState
 *           |
 *           | (GRPC connection established)
 *           V
 *    SelectingDatabaseState ---------ServerToClientMessage.SelectDatabaseResponse.Error---\
 *           |                                                                             |
 *           | ServerToClientMessage.SelectDatabaseResponse.Success                        |
 *           V                                                                             |
 *    SelectingFileState -----------------ServerToClientMessage.SelectFileResponse.Error---\
 *           |                                                                             |
 *           | ServerToClientMessage.SelectFileResponse.Success                            |
 *           |                                                                             |
 *           |      /-------\                                                              |
 *           V      V       |                                                              |
 *    DownloadingState      | ServerToClientMessage.GetChunksResponse                      |
 *           |   |  |       |     (still have chunks left)                                 |
 *           |   |  \-------/                                                              |
 *           |   |                                                                         |
 *           |   \-------------------------ServerToClientMessage.GetChunksResponse.Error---\
 *           |                                                                             |
 *           | ServerToClientMessage.GetChunksResponse (final chunk)                       |
 *           |                                                                             |
 *           |                                                            (Exceptions) ----\
 *    DisconnectingState                                                                   |
 *           |                                                             CancelEvent ----\
 *           |                                                                             |
 *           V                                                                             V
 *    SuccessState                                                                   FailedState
 *
 * </pre>
 */
public class LocalPirDownloadTask extends PirDownloadTask {
  private final SettableFuture<PirDownloadResult> future;
  private final PirClientFactory pirClientFactory;
  private final PirServerConnector pirServerConnector;
  private final Ticker ticker;

  private final FiniteStateMachine<BaseState, BaseEvent> stateMachine;
  private final AtomicInteger taskEventIndex;
  private final StatisticsTracker statisticsTracker;
  private final AtomicBoolean cancelled;

  protected LocalPirDownloadTask(Builder<?> builder) {
    super(builder);
    this.pirServerConnector = builder.pirServerConnector;
    this.pirClientFactory = Preconditions.checkNotNull(builder.pirClientFactory);
    this.ticker = Preconditions.checkNotNull(builder.ticker);

    this.taskEventIndex = new AtomicInteger();
    this.statisticsTracker = new StatisticsTracker(ticker);
    this.future = SettableFuture.create();
    this.stateMachine = new FiniteStateMachine<>(this.logger, new WaitingToRunState());
    this.cancelled = new AtomicBoolean(false);

    notifyTaskCreated(
        pirDownloadListener,
        pirUri.databaseName(),
        pirUri.databaseVersion(),
        taskId,
        taskEventIndex,
        statisticsTracker);
  }

  public static Builder<?> builder() {
    return new LocalBuilder();
  }

  @Override
  public void cancel() {
    this.cancelled.set(true);
    stateMachine.sendEvent(CancelEvent.create());
  }

  @Override
  public boolean hasBeenCancelled() {
    return this.cancelled.get();
  }

  @Override
  public @Nullable PirDownloadConstraints getDownloadConstraints() {
    return downloadConstraints;
  }

  /** Describe the state of the download task. */
  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  String describeState() {
    return stateMachine.describeState();
  }

  @Override
  public void run() {
    logger.logDebug("PirDownloadTask running; uri=%s", pirUri);
    stateMachine.run();
  }

  @Override
  public ListenableFuture<PirDownloadResult> getFuture() {
    return future;
  }

  /**
   * Register runnables to be executed the next time the PirDownloadTask's event queue is quiescent.
   *
   * <p>This method is intended to allow tests to send some events into the state machine and wait
   * for them to be fully processed before interrogating the state of the state machine.
   *
   * <p>Can be called from any thread.
   */
  @VisibleForTesting
  void runOnIdle(Runnable runnable) {
    stateMachine.runOnIdle(runnable);
  }

  /** Tracks statistics over the lifetime of the PIR download. */
  private class StatisticsTracker {
    // Time since the task was created.
    public final Stopwatch taskCreatedStopwatch;

    // Time since the task was started (i.e. exited the WaitingToRun state).
    public final Stopwatch taskStartedStopwatch;

    // Total number of bytes transmitted from the client to the server, including any protobuf
    // overhead.
    private long bytesSent;

    // Total number of bytes transmitted from the server to the client, including any protobuf
    // overhead.
    private long bytesReceived;

    /**
     * Creates a new StatisticsTracker using the given ticker.
     *
     * <p>Automatically starts the taskCreatedStopwatch.
     */
    public StatisticsTracker(Ticker ticker) {
      taskCreatedStopwatch = Stopwatch.createStarted(ticker);
      taskStartedStopwatch = Stopwatch.createUnstarted(ticker);
    }

    /**
     * Records that the task has started to run (i.e. has exited the WaitingToRun state).
     *
     * <p>Starts the taskStartedStopwatch.
     */
    public void recordTaskStarted() {
      taskStartedStopwatch.start();
    }

    /** Adds to the record of number of bytes transmitted from the client to the server. */
    public void recordBytesSent(long bytes) {
      bytesSent += bytes;
    }

    /** Adds to the record of the number of bytes transmitted from the server to the client. */
    public void recordBytesReceived(long bytes) {
      bytesReceived += bytes;
    }

    /** Writes the current statistics to the Android log stream. */
    public void logStatistics(String status) {
      logger.logDebug(
          "PIR download statistics: status=%s, %d bytes sent, %d bytes received, "
              + "%3fs since creation, %3fs since start",
          status,
          bytesSent,
          bytesReceived,
          taskCreatedStopwatch.elapsed(TimeUnit.MILLISECONDS) * 0.001,
          taskStartedStopwatch.elapsed(TimeUnit.MILLISECONDS) * 0.001);
    }
  }

  /** If the download constraints are no longer satisfied, ask the task to cancel itself. */
  private void checkDownloadConstraints() {
    if (downloadConstraints != null && !downloadConstraints.areSatisfied()) {
      cancel();
    }
  }

  // ========= FSM EVENTS =========

  /** Base class for all {@link FiniteStateMachine} events implementing the PIR download FSM. */
  abstract static class BaseEvent implements FiniteStateMachine.Event<BaseState> {}

  /**
   * Indicates that {@link LocalPirDownloadTask#cancel()} has been called and the FSM should start
   * an orderly termination.
   */
  @AutoValue
  abstract static class CancelEvent extends BaseEvent {
    public static CancelEvent create() {
      return new AutoValue_LocalPirDownloadTask_CancelEvent();
    }

    @Override
    public final @Nullable BaseState dispatchTo(BaseState state) throws PirDownloadException {
      return state.onCancel(this);
    }
  }

  /** Indicates that the event loop has started and we're ready to start executing the protocol. */
  @AutoValue
  abstract static class RunEvent extends BaseEvent {
    public static RunEvent create() {
      return new AutoValue_LocalPirDownloadTask_RunEvent();
    }

    @Override
    public final @Nullable BaseState dispatchTo(BaseState state) throws PirDownloadException {
      return state.onRun(this);
    }
  }

  /** Indicates that a message has arrived from the server. */
  @AutoValue
  abstract static class ServerToClientMessageEvent extends BaseEvent {
    abstract Logger logger();

    abstract ServerToClientMessage message();

    public static ServerToClientMessageEvent create(Logger logger, ServerToClientMessage message) {
      return new AutoValue_LocalPirDownloadTask_ServerToClientMessageEvent(logger, message);
    }

    @Override
    public final @Nullable BaseState dispatchTo(BaseState state) throws PirDownloadException {
      logger().logDebug("Dispatching message %s to %s", message().getKindCase(), state.describe());
      switch (message().getKindCase()) {
        case SELECT_DATABASE_RESPONSE:
          return state.onSelectDatabaseResponse(message().getSelectDatabaseResponse());
        case SELECT_FILE_RESPONSE:
          return state.onSelectFileResponse(message().getSelectFileResponse());
        case GET_CHUNKS_RESPONSE:
          return state.onGetChunksResponse(message().getGetChunksResponse());
        default:
          throw new IllegalStateException(
              String.format(Locale.US, "Unrecognized message type: %s", message().getKindCase()));
      }
    }
  }

  /**
   * Indicates that the server has half-closed the connection (i.e. {@link
   * StreamObserver#onClosed()} was called on the server-to-client {@link StreamObserver}.)
   */
  @AutoValue
  abstract static class ServerToClientHalfCloseEvent extends BaseEvent {
    public static ServerToClientHalfCloseEvent create() {
      return new AutoValue_LocalPirDownloadTask_ServerToClientHalfCloseEvent();
    }

    @Override
    public final @Nullable BaseState dispatchTo(BaseState state) throws PirDownloadException {
      return state.onServerToClientHalfClose(this);
    }
  }

  /**
   * Indicates that the server->client connection has received an error (i.e. {@link
   * StreamObserver#onError(Throwable)} was called on the server-to-client {@link StreamObserver}.)
   */
  @AutoValue
  abstract static class ServerToClientErrorEvent extends BaseEvent {
    abstract Throwable throwable();

    public static ServerToClientErrorEvent create(Throwable throwable) {
      return new AutoValue_LocalPirDownloadTask_ServerToClientErrorEvent(throwable);
    }

    @Override
    public final @Nullable BaseState dispatchTo(BaseState state) throws PirDownloadException {
      return state.onServerToClientError(this);
    }
  }

  // ========= FSM STATES =========

  /** Base class for all {@link FiniteStateMachine} states implementing the PIR download FSM. */
  private abstract class BaseState implements FiniteStateMachine.State<BaseState> {
    Stopwatch stateDurationStopwatch;

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      logger.logDebug("PirDownloadTask: Entering state %s", describe());
      notifyStateEntered(this);
      this.stateDurationStopwatch = Stopwatch.createStarted(ticker);
      checkDownloadConstraints();
      // Default behavior is to stay in the current state.
      return this;
    }

    @Override
    public void onExit() {
      notifyStateExited(this, stateDurationStopwatch);
      checkDownloadConstraints();
    }

    @Override
    public BaseState onException(Throwable t) {
      logger.logInfo(t, "PirDownloadTask: Exception %s in state %s", t, describe());
      PirDownloadException exception;
      if (t instanceof PirDownloadException) {
        exception = (PirDownloadException) t;
      } else {
        exception =
            PirDownloadException.builder()
                .withCause(t)
                .setMessage("Internal error.")
                .setIsPermanent(true)
                .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.INTERNAL_ERROR)
                .build();
      }
      notifyTaskFailure(this, exception.getLogsFailureCode());
      return new FailedState(exception);
    }

    public @Nullable BaseState onRun(RunEvent event) throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Unexpected Run Event in state %s", describe())
          .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_RUN_EVENT)
          .build();
    }

    /**
     * Handle a {@link SelectDatabaseResponse} message from the server.
     *
     * <p>Will only be called on the event loop thread.
     *
     * <p>Subclasses should override this method if {@link SelectDatabaseResponse} is expected in
     * the current state.
     */
    public @Nullable BaseState onSelectDatabaseResponse(SelectDatabaseResponse response)
        throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Unexpected SelectDatabaseResponse message in state %s", describe())
          .setLogsFailureCode(
              PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_SELECT_DATABASE_RESPONSE)
          .build();
    }

    /**
     * Handle a {@link SelectFileResponse} message from the server.
     *
     * <p>Will only be called on the event loop thread.
     *
     * <p>Subclasses should override this method if {@link SelectDatabaseResponse} is expected in
     * the current state.
     */
    public @Nullable BaseState onSelectFileResponse(SelectFileResponse response)
        throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Unexpected SelectFileResponse message in state %s", describe())
          .setLogsFailureCode(
              PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_SELECT_FILE_RESPONSE)
          .build();
    }

    /**
     * Handle a {@link GetChunksResponse} message from the server.
     *
     * <p>Will only be called on the event loop thread.
     *
     * <p>Subclasses should override this method if {@link GetChunksResponse} is expected in the
     * current state.
     */
    public @Nullable BaseState onGetChunksResponse(GetChunksResponse response)
        throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Unexpected GetChunksResponse message in state %s", describe())
          .setLogsFailureCode(
              PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_GET_CHUNKS_RESPONSE)
          .build();
    }

    /**
     * Handle a {@link CancelEvent}.
     *
     * <p>Will only be called on the event loop thread.
     *
     * <p>Subclasses generally don't need to override this method. All events (including {@link
     * CancelEvent}s) will be ignored in terminal states. In non-terminal states, Cancel events
     * should transition the FSM to the {@link FailedState}, as they do here.
     */
    public @Nullable BaseState onCancel(CancelEvent event) throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Download task was cancelled.")
          .setIsCancellation(true)
          .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.CANCELLED)
          .build();
    }

    /**
     * Handle a {@link ServerToClientHalfCloseEvent}.
     *
     * <p>Will only be called on the event loop thread.
     *
     * <p>Subclasses generally don't need to override this method. All events (including {@link
     * ServerToClientHalfCloseEvent}s) will be ignored in terminal states. In non-terminal states,
     * {@link ServerToClientHalfCloseEvent}s are unexpected and indicate that an error has occurred.
     */
    public @Nullable BaseState onServerToClientHalfClose(ServerToClientHalfCloseEvent event)
        throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Unexpected ServerToClientHalfCloseEvent in state %s", describe())
          .setLogsFailureCode(
              PirDownloadTaskEvent.TaskCompleted.Failure.Code.SERVER_STREAM_UNEXPECTED_HALF_CLOSE)
          .build();
    }

    /**
     * Handle a {@link ServerToClientErrorEvent}.
     *
     * <p>Will only be called on the event loop thread.
     *
     * <p>Subclasses generally don't need to override this method; errors from the server-to-client
     * channel are error conditions for the FSM as a whole.
     */
    public @Nullable BaseState onServerToClientError(ServerToClientErrorEvent event)
        throws PirDownloadException {
      throw PirDownloadException.builder()
          .withCause(event.throwable())
          .setMessage("Unexpected ServerToClientErrorEvent in state %s", describe())
          .setLogsFailureCode(
              PirDownloadTaskEvent.TaskCompleted.Failure.Code.SERVER_STREAM_UNEXPECTED_ERROR)
          .build();
    }

    abstract PirDownloadTaskEvent.DownloadTaskState getLogCodeForState();
  }

  /**
   * The state machine starts in this state and stays here until {@link LocalPirDownloadTask#run()}
   * launches the event loop.
   */
  private class WaitingToRunState extends BaseState {
    @Override
    public String describe() {
      return "WaitingToRun";
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      // We send a RunEvent and wait for onRun so that any CancelEvents already in the event queue
      // have a chance to be handled before we transition to the Connecting state and start
      // interacting with the outside world.
      stateMachine.sendEvent(RunEvent.create());
      return this;
    }

    @Override
    public @Nullable BaseState onRun(RunEvent event) {
      checkDownloadConstraints();
      return new ConnectingState();
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.WAITING_TO_RUN;
    }
  }

  private class ConnectingState extends BaseState {
    @Override
    public String describe() {
      return "Connecting";
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      statisticsTracker.recordTaskStarted();
      // Wire StreamObserver events (which may occur on any thread) to send messages into the
      // state machine (where they will only be processed on the event loop thread.)
      StreamObserver<ServerToClientMessage> serverToClientMessageHandler =
          new StreamObserver<ServerToClientMessage>() {
            @Override
            public void onNext(ServerToClientMessage value) {
              logger.logDebug("Received message from server: %s", value.getKindCase());
              statisticsTracker.recordBytesReceived(value.getSerializedSize());
              notifyMessageReceived(value);
              stateMachine.sendEvent(ServerToClientMessageEvent.create(logger, value));
            }

            @Override
            public void onError(Throwable t) {
              logger.logDebug(t, "Received error from server");
              notifyCommunicationStreamStatusChange(
                  PirDownloadTaskEvent.Communication.StreamStatusChange.StatusCode
                      .STREAM_ERROR_RECEIVED);
              stateMachine.sendEvent(ServerToClientErrorEvent.create(t));
            }

            @Override
            public void onCompleted() {
              logger.logDebug("Received half-close from server");
              notifyCommunicationStreamStatusChange(
                  PirDownloadTaskEvent.Communication.StreamStatusChange.StatusCode
                      .STREAM_COMPLETED_RECEIVED);
              stateMachine.sendEvent(ServerToClientHalfCloseEvent.create());
            }
          };

      // Establish the connection to the server.
      ManagedChannel channel = pirServerConnector.createChannel(pirUri);
      try {
        final StreamObserver<ClientToServerMessage> clientToServerMessageSender =
            pirServerConnector.createSession(channel, apiKey, serverToClientMessageHandler);
        StreamObserver<ClientToServerMessage> clientToServerMessageSenderWithLogging =
            new StreamObserver<ClientToServerMessage>() {
              @Override
              public void onNext(ClientToServerMessage value) {
                logger.logDebug("Sending message to server: %s", value.getKindCase());
                statisticsTracker.recordBytesSent(value.getSerializedSize());
                clientToServerMessageSender.onNext(value);
                notifyMessageSent(value);
              }

              @Override
              public void onError(Throwable t) {
                logger.logDebug(t, "Sending error to server");
                notifyCommunicationStreamStatusChange(
                    PirDownloadTaskEvent.Communication.StreamStatusChange.StatusCode
                        .STREAM_ERROR_SENT);
                clientToServerMessageSender.onError(t);
              }

              @Override
              public void onCompleted() {
                logger.logDebug("Sending half-close to server");
                notifyCommunicationStreamStatusChange(
                    PirDownloadTaskEvent.Communication.StreamStatusChange.StatusCode
                        .STREAM_COMPLETED_SENT);
                clientToServerMessageSender.onCompleted();
              }
            };
        return new SelectingDatabaseState(channel, clientToServerMessageSenderWithLogging);
      } catch (Exception e) {
        channel.shutdown();
        throw e;
      }
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.CONNECTING;
    }
  }

  private abstract class ConnectedBaseState extends BaseState {
    protected final ManagedChannel channel;
    protected final StreamObserver<ClientToServerMessage> clientToServerMessageSender;

    protected ConnectedBaseState(
        ManagedChannel channel, StreamObserver<ClientToServerMessage> clientToServerMessageSender) {
      this.channel = channel;
      this.clientToServerMessageSender = clientToServerMessageSender;
    }

    @Override
    public BaseState onException(Throwable t) {
      clientToServerMessageSender.onError(t);
      channel.shutdown();
      return super.onException(t);
    }
  }

  /**
   * In this state, we send {@link SelectDatabaseRequest} and await a {@link
   * SelectDatabaseResponse}.
   */
  private class SelectingDatabaseState extends ConnectedBaseState {
    public SelectingDatabaseState(
        ManagedChannel channel, StreamObserver<ClientToServerMessage> clientToServerMessageSender) {
      super(channel, clientToServerMessageSender);
    }

    @Override
    public String describe() {
      return "SelectingDatabase";
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      clientToServerMessageSender.onNext(
          ClientToServerMessage.newBuilder()
              .setSelectDatabaseRequest(
                  ClientToServerMessage.SelectDatabaseRequest.newBuilder()
                      .setDatabase(pirUri.databaseName())
                      .setVersion(pirUri.databaseVersion()))
              .build());
      return this;
    }

    @Override
    public @Nullable BaseState onSelectDatabaseResponse(
        ServerToClientMessage.SelectDatabaseResponse response) throws PirDownloadException {
      checkDownloadConstraints();
      switch (response.getOutcomeCase()) {
        case SUCCESS:
          return onSuccessOutcome(response.getSuccess());
        case ERROR:
          return onErrorOutcome(response.getError());
        default:
          throw new IllegalStateException(
              String.format(Locale.US, "Unrecognized output: %s", response.getOutcomeCase()));
      }
    }

    private @Nullable BaseState onSuccessOutcome(
        ServerToClientMessage.SelectDatabaseResponse.Success success) {
      ConfigurationParameters configurationParameters = success.getConfigurationParameters();
      PirClient pirClient = pirClientFactory.create(configurationParameters);
      return new SelectingFileState(
          channel, configurationParameters, pirClient, clientToServerMessageSender);
    }

    private @Nullable BaseState onErrorOutcome(
        ServerToClientMessage.SelectDatabaseResponse.Error error) throws PirDownloadException {
      switch (error.getErrorCodeCase()) {
        case NO_SUCH_DATABASE:
          throw PirDownloadException.builder()
              .setMessage("Select Database failed; no such database")
              .setIsPermanent(true)
              .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.NO_SUCH_DATABASE)
              .build();
        default:
          throw PirDownloadException.builder()
              .setMessage(
                  "Select Database failed; unrecognized error code: %s", error.getErrorCodeCase())
              .setIsPermanent(true)
              .setLogsFailureCode(
                  PirDownloadTaskEvent.TaskCompleted.Failure.Code
                      .UNRECOGNIZED_SELECT_DATABASE_FAILURE)
              .build();
      }
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.SELECTING_DATABASE;
    }
  }

  /**
   * In this state, we send {@link SelectFileRequest} and await a {@link SelectFileResponse}.
   *
   * <p>This state is also where the local {@link PirClient} implementation is instantiated, using
   * the database configuration obtained from the server in the {@link SelectingDatabaseState}.
   */
  private class SelectingFileState extends ConnectedBaseState {
    private final ConfigurationParameters configurationParameters;
    private final PirClient pirClient;

    public SelectingFileState(
        ManagedChannel channel,
        ConfigurationParameters configurationParameters,
        PirClient pirClient,
        StreamObserver<ClientToServerMessage> clientToServerMessageSender) {
      super(channel, clientToServerMessageSender);
      this.configurationParameters = configurationParameters;
      this.pirClient = pirClient;
    }

    @Override
    public String describe() {
      return "SelectingFile";
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      int numberOfEntries = PirUtil.getNumberOfEntries(configurationParameters);
      if (pirUri.entry() < 0 || pirUri.entry() >= numberOfEntries) {
        throw PirDownloadException.builder()
            .setMessage("Invalid entry index in Pir Uri")
            .setIsPermanent(true)
            .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.INVALID_ENTRY_INDEX)
            .build();
      }

      try {
        Stopwatch beginSessionDurationStopwatch = Stopwatch.createStarted(ticker);
        pirClient.beginSession(pirUri.entry());
        notifyCryptoOperationBeginSession(beginSessionDurationStopwatch);
      } catch (Exception e) {
        throw PirDownloadException.builder()
            .withCause(e)
            .setMessage("Failed to begin PIR session.")
            .setLogsFailureCode(
                PirDownloadTaskEvent.TaskCompleted.Failure.Code.BEGIN_SESSION_FAILED)
            .build();
      }

      Stopwatch createRequestDurationStopwatch = Stopwatch.createStarted(ticker);
      PirRequest request = pirClient.createRequest();
      notifyCryptoOperationCreateRequest(createRequestDurationStopwatch);
      logger.logDebug("Request=%s", request);
      clientToServerMessageSender.onNext(
          ClientToServerMessage.newBuilder()
              .setSelectFileRequest(
                  ClientToServerMessage.SelectFileRequest.newBuilder().setQuery(request))
              .build());
      return this;
    }

    @Override
    public @Nullable BaseState onSelectFileResponse(
        ServerToClientMessage.SelectFileResponse response) throws PirDownloadException {
      checkDownloadConstraints();
      switch (response.getOutcomeCase()) {
        case SUCCESS:
          return onSuccessOutcome(response.getSuccess());
        case ERROR:
          return onErrorOutcome(response.getError());
        default:
          throw new IllegalStateException(
              String.format(Locale.US, "Unrecognized output: %s", response.getOutcomeCase()));
      }
    }

    private @Nullable BaseState onSuccessOutcome(
        ServerToClientMessage.SelectFileResponse.Success unusedSuccess)
        throws PirDownloadException {
      long startingChunk;
      try {
        // If there's a partial download, start from a later chunk. Default is chunk 0.
        startingChunk =
            PirUtil.getChunkIndex(configurationParameters, responseWriter.getNumExistingBytes());
      } catch (Exception e) {
        // An error was encountered with resuming from a partial download. We delete the partial
        // file if one exists, so that subsequent download attempts start from scratch.
        try {
          responseWriter.deleteOutput();
        } catch (Exception e2) {
          throw PirDownloadException.builder()
              .withCause(e2)
              .setMessage(
                  "Failed to delete the partially downloaded file after detecting a corruption.")
              .setLogsFailureCode(
                  PirDownloadTaskEvent.TaskCompleted.Failure.Code.FAILED_TO_DELETE_PARTIAL_DOWNLOAD)
              .build();
        }
        // Partial download was successfully deleted.
        throw PirDownloadException.builder()
            .withCause(e)
            .setMessage(
                "Failed to resume partial download, may be corrupt or already complete. The output"
                    + " has been deleted so subsequent retries start from scratch.")
            .setLogsFailureCode(
                PirDownloadTaskEvent.TaskCompleted.Failure.Code.BAD_PARTIAL_DOWNLOAD)
            .build();
      }
      return new DownloadingState(
          channel,
          configurationParameters,
          pirClient,
          clientToServerMessageSender,
          (int) startingChunk,
          numChunksPerRequest);
    }

    private BaseState onErrorOutcome(ServerToClientMessage.SelectFileResponse.Error unusedError)
        throws PirDownloadException {
      throw PirDownloadException.builder()
          .setMessage("Select File failed")
          .setIsPermanent(true)
          .setLogsFailureCode(PirDownloadTaskEvent.TaskCompleted.Failure.Code.SELECT_FILE_FAILED)
          .build();
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.SELECTING_FILE;
    }
  }

  /**
   * In this state, we send a sequence of {@link GetChunksRequest}s and await matching {@link
   * GetChunksResponse}s.
   */
  private class DownloadingState extends ConnectedBaseState {
    private final PirClient pirClient;
    private final int numberOfChunks;
    private final long chunkSizeBytes;
    private final long entrySizeBytes;
    private final int numChunksPerRequest;
    private int firstChunkInCurrentChunkRange;
    private int lastChunkInCurrentChunkRange;

    public DownloadingState(
        ManagedChannel channel,
        ConfigurationParameters configurationParameters,
        PirClient pirClient,
        StreamObserver<ClientToServerMessage> clientToServerMessageSender,
        int startingChunk,
        int numChunksPerRequest) {
      super(channel, clientToServerMessageSender);
      this.pirClient = pirClient;
      this.numberOfChunks = PirUtil.getNumberOfChunks(configurationParameters);
      this.chunkSizeBytes = PirUtil.getChunkSizeBytes(configurationParameters);
      this.entrySizeBytes = PirUtil.getEntrySizeBytes(configurationParameters);
      this.numChunksPerRequest = numChunksPerRequest;
      this.firstChunkInCurrentChunkRange = startingChunk;
      this.lastChunkInCurrentChunkRange =
          Math.min(firstChunkInCurrentChunkRange + numChunksPerRequest, numberOfChunks) - 1;
      pirDownloadListener.onNumberOfChunksDetermined(numberOfChunks);
    }

    @Override
    public String describe() {
      return String.format(
          Locale.US,
          "Downloading[chunk range %d to %d of %d]",
          firstChunkInCurrentChunkRange,
          lastChunkInCurrentChunkRange,
          numberOfChunks);
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      return maybeRequestChunks();
    }

    /**
     * Either request a group of chunks (if there are remaining chunks) or transition to the {@link
     * DisconnectingState} (if all chunks have been downloaded already).
     */
    private @Nullable BaseState maybeRequestChunks() {
      int chunksRemaining = numberOfChunks - firstChunkInCurrentChunkRange;
      if (chunksRemaining <= 0) {
        return new DisconnectingState(channel, clientToServerMessageSender);
      }

      int chunksToRequest = lastChunkInCurrentChunkRange - firstChunkInCurrentChunkRange + 1;

      logger.logDebug(
          "PirDownloadTask requesting chunk %d to chunk %d",
          firstChunkInCurrentChunkRange, lastChunkInCurrentChunkRange);
      clientToServerMessageSender.onNext(
          ClientToServerMessage.newBuilder()
              .setGetChunksRequest(
                  ClientToServerMessage.GetChunksRequest.newBuilder()
                      .addChunkRanges(
                          ClientToServerMessage.GetChunksRequest.ChunkRange.newBuilder()
                              .setMinChunkNumber(firstChunkInCurrentChunkRange)
                              .setCount(chunksToRequest)))
              .build());
      return this;
    }

    @Override
    public @Nullable BaseState onGetChunksResponse(ServerToClientMessage.GetChunksResponse response)
        throws PirDownloadException {
      checkDownloadConstraints();
      switch (response.getOutcomeCase()) {
        case SUCCESS:
          return onSuccessOutcome(response.getSuccess());
        case ERROR:
          return onErrorOutcome(response.getError());
        default:
          throw new IllegalStateException(
              String.format(Locale.US, "Unrecognized output: %s", response.getOutcomeCase()));
      }
    }

    private @Nullable BaseState onSuccessOutcome(
        ServerToClientMessage.GetChunksResponse.Success success) throws PirDownloadException {
      if (success.getChunksCount() != 1) {
        throw PirDownloadException.builder()
            .setMessage("PIR server did not return a single range of chunks")
            .setIsPermanent(true)
            .setLogsFailureCode(
                PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_NUMBER_OF_CHUNK_RANGES)
            .build();
      }

      ServerToClientMessage.GetChunksResponse.Success.Chunks chunks = success.getChunks(0);
      if (chunks.getMinChunkNumber() != firstChunkInCurrentChunkRange) {
        throw PirDownloadException.builder()
            .setMessage(
                "PIR server returned chunks starting from an unexpected index"
                    + " (expected: %d, received: %d).",
                firstChunkInCurrentChunkRange, chunks.getMinChunkNumber())
            .setIsPermanent(true)
            .setLogsFailureCode(
                PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_CHUNK_INDEX)
            .build();
      }

      int expectedNumChunksInResponse =
          lastChunkInCurrentChunkRange - firstChunkInCurrentChunkRange + 1;

      if (chunks.getChunkCount() != expectedNumChunksInResponse) {
        throw PirDownloadException.builder()
            .setMessage(
                "PIR server returned 1 chunk range, but not exactly %d chunks in that range.",
                expectedNumChunksInResponse)
            .setIsPermanent(true)
            .setLogsFailureCode(
                PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNEXPECTED_NUMBER_OF_CHUNKS)
            .build();
      }

      // Process each chunk in the response.
      for (int i = 0; i < expectedNumChunksInResponse; i++) {
        PirResponseChunk chunk = chunks.getChunk(i);

        Stopwatch processResponseChunkDurationStopwatch = Stopwatch.createStarted(ticker);
        byte[] processed = pirClient.processResponseChunk(chunk);
        notifyCryptoOperationProcessResponseChunk(
            firstChunkInCurrentChunkRange + i, processResponseChunkDurationStopwatch);

        long downloadOffsetBytes = chunkSizeBytes * (firstChunkInCurrentChunkRange + i);
        int countBytes = (int) Math.min(chunkSizeBytes, entrySizeBytes - downloadOffsetBytes);
        try {
          responseWriter.writeResponse(
              processed, downloadOffsetBytes, 0 /* dataBufferOffsetBytes */, countBytes);
        } catch (IOException e) {
          throw PirDownloadException.builder()
              .withCause(e)
              .setMessage("IOException occurred while writing processed PIR chunks to disk.")
              .setLogsFailureCode(
                  PirDownloadTaskEvent.TaskCompleted.Failure.Code.IO_ERROR_WRITING_TO_DISK)
              .build();
        }
      }
      // Advance to the next chunk range, or transition to the Success state if this was the last
      // set of chunks.
      firstChunkInCurrentChunkRange = lastChunkInCurrentChunkRange + 1;
      lastChunkInCurrentChunkRange =
          Math.min(firstChunkInCurrentChunkRange + numChunksPerRequest, numberOfChunks) - 1;
      return maybeRequestChunks();
    }

    private @Nullable BaseState onErrorOutcome(ServerToClientMessage.GetChunksResponse.Error error)
        throws PirDownloadException {
      switch (error.getErrorCodeCase()) {
        case INVALID_CHUNK_RANGE:
          throw PirDownloadException.builder()
              .setMessage("Get Chunks failed; invalid chunk range.")
              .setIsPermanent(true)
              .setLogsFailureCode(
                  PirDownloadTaskEvent.TaskCompleted.Failure.Code.SERVER_REJECTED_CHUNK_RANGE)
              .build();
        default:
          throw PirDownloadException.builder()
              .setMessage(
                  "Get Chunks failed; unrecognized error code: %s", error.getErrorCodeCase())
              .setIsPermanent(true)
              .setLogsFailureCode(
                  PirDownloadTaskEvent.TaskCompleted.Failure.Code.UNRECOGNIZED_GET_CHUNKS_FAILURE)
              .build();
      }
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.DOWNLOADING;
    }
  }

  /** Shuts down the communications channel, then proceeds to {@link SuccessState}. */
  private class DisconnectingState extends ConnectedBaseState {
    DisconnectingState(
        ManagedChannel channel, StreamObserver<ClientToServerMessage> clientToServerMessageSender) {
      super(channel, clientToServerMessageSender);
    }

    @Override
    public String describe() {
      return "Disconnecting";
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      clientToServerMessageSender.onCompleted();
      channel.shutdown();
      return new SuccessState();
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.DISCONNECTING;
    }
  }

  /**
   * Terminal state indicating that the PIR download succeeded.
   *
   * <p>Sets the {@link PirDownloadResult} on the {@link Future} supplied when the {@link
   * LocalPirDownloadTask} was created and notifies {@link PirDownloadListener#onSuccess}.
   */
  private class SuccessState extends BaseState {
    @Override
    public String describe() {
      return "Success";
    }

    @Override
    public @Nullable BaseState onEnter() throws PirDownloadException {
      super.onEnter();
      statisticsTracker.logStatistics("Success");
      future.set(PirDownloadResult.create(pirUri));
      notifyTaskSuccess();
      pirDownloadListener.onSuccess(pirUri);
      return null; // Terminate the FSM.
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.SUCCESS;
    }
  }

  /**
   * Terminal state indicating that the PIR download failed.
   *
   * <p>Sets an {@link Exception} on the {@link Future} supplied when the {@link
   * LocalPirDownloadTask} was created and notifies {@link PirDownloadListener#onFailure}.
   */
  private class FailedState extends BaseState {
    private final PirDownloadException exception;

    public FailedState(PirDownloadException exception) {
      this.exception = exception;
    }

    @Override
    public String describe() {
      if (exception != null
          && exception.getMessage() != null
          && !exception.getMessage().isEmpty()) {
        return String.format(Locale.US, "Failed[%s]", exception.getMessage());
      } else {
        return "Failed";
      }
    }

    @Override
    public @Nullable BaseState onEnter() {
      // FailedState.onEnter must not throw exceptions; otherwise, the FiniteStateMachine
      // may enter an infinite loop.
      try {
        super.onEnter();
        statisticsTracker.logStatistics("Failed");
        future.setException(exception);
        pirDownloadListener.onFailure(pirUri, exception);
      } catch (Exception e) {
        logger.logErr(
            "Additional failure occurred while entering FailedState; pirUri=%s, original"
                + " exception=%s, new exception=%s",
            pirUri, exception, e);
      }
      return null; // Terminate the FSM.
    }

    @Override
    PirDownloadTaskEvent.DownloadTaskState getLogCodeForState() {
      return PirDownloadTaskEvent.DownloadTaskState.FAILED;
    }
  }

  // Must be static so that it can be used from the constructor.
  private static void notifyTaskCreated(
      PirDownloadListener pirDownloadListener,
      String databaseName,
      String databaseVersion,
      long taskId,
      AtomicInteger taskEventIndex,
      StatisticsTracker statisticsTracker) {
    notifyPirDownloadTaskEvent(
        pirDownloadListener,
        databaseName,
        databaseVersion,
        taskId,
        taskEventIndex,
        statisticsTracker,
        PirDownloadTaskEvent.newBuilder()
            .setTaskCreated(PirDownloadTaskEvent.TaskCreated.getDefaultInstance()));
  }

  private void notifyTaskSuccess() {
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setTaskCompleted(
                PirDownloadTaskEvent.TaskCompleted.newBuilder()
                    .setSuccess(PirDownloadTaskEvent.TaskCompleted.Success.getDefaultInstance())));
  }

  private void notifyTaskFailure(
      BaseState fromState, PirDownloadTaskEvent.TaskCompleted.Failure.Code failureCode) {
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setTaskCompleted(
                PirDownloadTaskEvent.TaskCompleted.newBuilder()
                    .setFailure(
                        PirDownloadTaskEvent.TaskCompleted.Failure.newBuilder()
                            .setFailureCode(failureCode)
                            .setFromState(fromState.getLogCodeForState()))));
  }

  private void notifyStateEntered(BaseState state) {
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setStateTransition(
                PirDownloadTaskEvent.StateTransition.newBuilder()
                    .setEntered(
                        PirDownloadTaskEvent.StateTransition.Entered.newBuilder()
                            .setState(state.getLogCodeForState()))));
  }

  private void notifyStateExited(BaseState state, Stopwatch durationStopwatch) {
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setStateTransition(
                PirDownloadTaskEvent.StateTransition.newBuilder()
                    .setExited(
                        PirDownloadTaskEvent.StateTransition.Exited.newBuilder()
                            .setState(state.getLogCodeForState())
                            .setDurationMs(durationStopwatch.elapsed(TimeUnit.MILLISECONDS)))));
  }

  private void notifyCryptoOperationBeginSession(Stopwatch durationStopwatch) {
    notifyCryptoOperation(
        PirDownloadTaskEvent.CryptoOperation.newBuilder()
            .setBeginSession(
                PirDownloadTaskEvent.CryptoOperation.BeginSession.getDefaultInstance()),
        durationStopwatch);
  }

  private void notifyCryptoOperationCreateRequest(Stopwatch durationStopwatch) {
    notifyCryptoOperation(
        PirDownloadTaskEvent.CryptoOperation.newBuilder()
            .setCreateRequest(
                PirDownloadTaskEvent.CryptoOperation.CreateRequest.getDefaultInstance()),
        durationStopwatch);
  }

  private void notifyCryptoOperationProcessResponseChunk(
      int chunkNumber, Stopwatch durationStopwatch) {
    notifyCryptoOperation(
        PirDownloadTaskEvent.CryptoOperation.newBuilder()
            .setProcessResponseChunk(
                PirDownloadTaskEvent.CryptoOperation.ProcessResponseChunk.newBuilder()
                    .setChunkNumber(chunkNumber)),
        durationStopwatch);
  }

  private void notifyCryptoOperation(
      PirDownloadTaskEvent.CryptoOperation.Builder cryptoOperationBuilder,
      Stopwatch durationStopwatch) {
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setCryptoOperation(
                cryptoOperationBuilder.setDurationMs(
                    durationStopwatch.elapsed(TimeUnit.MILLISECONDS))));
  }

  private void notifyMessageSent(ClientToServerMessage message) {
    PirDownloadTaskEvent.Communication.MessageSent.Builder sentBuilder =
        PirDownloadTaskEvent.Communication.MessageSent.newBuilder();
    switch (message.getKindCase()) {
      case KIND_NOT_SET:
        break;
      case SELECT_DATABASE_REQUEST:
        sentBuilder.setSelectDatabaseRequest(
            PirDownloadTaskEvent.Communication.MessageSent.SelectDatabaseRequest
                .getDefaultInstance());
        break;
      case SELECT_FILE_REQUEST:
        sentBuilder.setSelectFileRequest(
            PirDownloadTaskEvent.Communication.MessageSent.SelectFileRequest.getDefaultInstance());
        break;
      case GET_CHUNKS_REQUEST:
        PirDownloadTaskEvent.Communication.MessageSent.GetChunksRequest.Builder
            getChunksRequestBuilder =
                PirDownloadTaskEvent.Communication.MessageSent.GetChunksRequest.newBuilder();
        for (ClientToServerMessage.GetChunksRequest.ChunkRange messageChunks :
            message.getGetChunksRequest().getChunkRangesList()) {
          getChunksRequestBuilder.addChunkRanges(
              PirDownloadTaskEvent.Communication.MessageSent.GetChunksRequest.ChunkRange
                  .newBuilder()
                  .setMinChunkNumber(messageChunks.getMinChunkNumber())
                  .setCount(messageChunks.getCount()));
        }
        sentBuilder.setGetChunksRequest(getChunksRequestBuilder);
        break;
    }
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setCommunication(
                PirDownloadTaskEvent.Communication.newBuilder()
                    .setMessageSent(sentBuilder.setSizeBytes(message.getSerializedSize()))));
  }

  private void notifyMessageReceived(ServerToClientMessage message) {
    PirDownloadTaskEvent.Communication.MessageReceived.Builder receivedBuilder =
        PirDownloadTaskEvent.Communication.MessageReceived.newBuilder();
    switch (message.getKindCase()) {
      case KIND_NOT_SET:
        break;
      case SELECT_DATABASE_RESPONSE:
        receivedBuilder.setSelectDatabaseResponse(
            PirDownloadTaskEvent.Communication.MessageReceived.SelectDatabaseResponse
                .getDefaultInstance());
        break;
      case SELECT_FILE_RESPONSE:
        receivedBuilder.setSelectFileResponse(
            PirDownloadTaskEvent.Communication.MessageReceived.SelectFileResponse
                .getDefaultInstance());
        break;
      case GET_CHUNKS_RESPONSE:
        PirDownloadTaskEvent.Communication.MessageReceived.GetChunksResponse.Builder
            getChunksResponseBuilder =
                PirDownloadTaskEvent.Communication.MessageReceived.GetChunksResponse.newBuilder();
        for (ServerToClientMessage.GetChunksResponse.Success.Chunks messageChunks :
            message.getGetChunksResponse().getSuccess().getChunksList()) {
          getChunksResponseBuilder.addChunkRanges(
              PirDownloadTaskEvent.Communication.MessageReceived.GetChunksResponse.ChunkRange
                  .newBuilder()
                  .setMinChunkNumber(messageChunks.getMinChunkNumber())
                  .setCount(messageChunks.getChunkCount()));
        }
        receivedBuilder.setGetChunksResponse(getChunksResponseBuilder);
        break;
      case EMPTY_REQUEST_ERROR:
        receivedBuilder.setEmptyRequestError(
            PirDownloadTaskEvent.Communication.MessageReceived.EmptyRequestError
                .getDefaultInstance());
        break;
    }
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setCommunication(
                PirDownloadTaskEvent.Communication.newBuilder()
                    .setMessageReceived(
                        receivedBuilder.setSizeBytes(message.getSerializedSize()))));
  }

  private void notifyCommunicationStreamStatusChange(
      PirDownloadTaskEvent.Communication.StreamStatusChange.StatusCode status) {
    notifyPirDownloadTaskEvent(
        PirDownloadTaskEvent.newBuilder()
            .setCommunication(
                PirDownloadTaskEvent.Communication.newBuilder()
                    .setStreamStatusChange(
                        PirDownloadTaskEvent.Communication.StreamStatusChange.newBuilder()
                            .setStatus(status))));
  }

  private void notifyPirDownloadTaskEvent(PirDownloadTaskEvent.Builder taskEventBuilder) {
    notifyPirDownloadTaskEvent(
        pirDownloadListener,
        pirUri.databaseName(),
        pirUri.databaseVersion(),
        taskId,
        taskEventIndex,
        statisticsTracker,
        taskEventBuilder);
  }

  // This static version of notifyPirDownloadTaskEvent exists so that we can use it from the
  // constructor.
  private static void notifyPirDownloadTaskEvent(
      PirDownloadListener pirDownloadListener,
      String databaseName,
      String databaseVersion,
      long taskId,
      AtomicInteger taskEventIndex,
      StatisticsTracker statisticsTracker,
      PirDownloadTaskEvent.Builder taskEventBuilder) {
    if (pirDownloadListener == null) {
      return;
    }
    pirDownloadListener.onPirEvent(
        PirEvent.newBuilder()
            .setPirDownloadTaskEvent(
                taskEventBuilder
                    .setDatabaseName(databaseName)
                    .setDatabaseVersion(databaseVersion)
                    .setTaskId(taskId)
                    .setTimeSinceTaskCreationMs(
                        statisticsTracker.taskCreatedStopwatch.elapsed(TimeUnit.MILLISECONDS))
                    .setTaskEventIndex(taskEventIndex.getAndIncrement()))
            .build());
  }

  /**
   * Builder for this specific implementation, on top of the interface {@link
   * PirDownloadTask.Builder}.
   */
  public abstract static class Builder<T extends Builder<T>> extends PirDownloadTask.Builder<T> {
    private @Nullable PirClientFactory pirClientFactory;
    private PirServerConnector pirServerConnector;
    private @Nullable Ticker ticker;

    @Override
    public PirDownloadTask build() throws PirDownloadException {
      return new LocalPirDownloadTask(this);
    }

    public T setPirClientFactory(PirClientFactory pirClientFactory) {
      this.pirClientFactory = pirClientFactory;
      return self();
    }

    public T setTicker(Ticker ticker) {
      this.ticker = ticker;
      return self();
    }

    public T setPirServerConnector(PirServerConnector pirServerConnector) {
      this.pirServerConnector = pirServerConnector;
      return self();
    }
  }

  private static class LocalBuilder extends Builder<LocalBuilder> {
    @Override
    protected LocalBuilder self() {
      return this;
    }
  }
}
