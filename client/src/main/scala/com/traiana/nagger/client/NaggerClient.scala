package com.traiana.nagger.client

import akka.actor.Status.Failure
import akka.actor.{ActorLogging, ActorRef, ActorSystem, FSM, Props, Stash}
import akka.pattern.pipe
import NaggerClient._
import com.traiana.nagger.spb._
import com.traiana.nagger.spb.LoginRegisterResponse.Response
import com.traiana.nagger.spb.NaggerGrpc.NaggerStub
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.concurrent.duration._

object NaggerClient {
  def main(args: Array[String]): Unit = {
    val system       = ActorSystem("nagger-client")
    val naggerClient = system.actorOf(NaggerClient.props, "nagger-client")
    val _            = system.actorOf(ConsoleClient.props(naggerClient), "console-client")

    system.registerOnTermination(System.exit(0))
  }

  def props: Props = Props(new NaggerClient)

  case object CheckGrpcState
  case object ChatCompleted
  case class ChatError(t: Throwable)

  sealed trait ClientState
  final case object Disconnected extends ClientState
  final case object Connected    extends ClientState
  final case object LoggedIn     extends ClientState

  sealed trait ClientData {
    def currentChannel: Option[String]
    def withCurrentChannel(channel: Option[String]): ClientData = this match {
      case d: DisconnectedData => d.copy(currentChannel = channel)
      case d: LoggedInData     => d.copy(currentChannel = channel)
      case d: LoggedOutData    => d.copy(currentChannel = channel)
    }
    def finish(): Unit = {}
  }
  sealed trait ConnectedData extends ClientData {
    def channel: ManagedChannel
    def stub: NaggerStub

    def isDisconnected: Boolean = channel.isShutdown || channel.isTerminated
    override def finish(): Unit = {
      channel.shutdown()
    }
  }

  final case class DisconnectedData(currentChannel: Option[String]) extends ClientData
  final case class LoggedOutData(channel: ManagedChannel, stub: NaggerStub, currentChannel: Option[String])
      extends ConnectedData
  final case class LoggedInData(channel: ManagedChannel,
                                stub: NaggerStub,
                                token: String,
                                currentChannel: Option[String])
      extends ConnectedData {
    def startChatting(implicit self: ActorRef): Unit = {
      val resps = new StreamObserver[ListenEvent] {
        override def onError(t: Throwable): Unit      = self ! ChatError(t)
        override def onCompleted(): Unit              = self ! ChatCompleted
        override def onNext(value: ListenEvent): Unit = self ! value
      }

      val req = ListenRequest(token)
      stub.listen(req, resps)
    }

    def join(chName: String): Unit = {
      val req = JoinLeaveRequest(token, chName, joinNotLeave = true)
      stub.joinLeave(req)
    }

    def leave(chName: String): Unit = {
      val req = JoinLeaveRequest(token, chName, joinNotLeave = false)
      stub.joinLeave(req)
    }

    def message(msg: String): Unit = currentChannel foreach { ch =>
      val req = MessageRequest(token, ch, msg)
      stub.sendMessage(req)
    }
  }

  private val timerName = "nagger-client-timer"
}

class NaggerClient extends FSM[ClientState, ClientData] with ActorLogging with Stash {
  private var loginDetails: Option[LoginRequest] = None

  startWith(Connected, connect())

  when(Disconnected) {
    case Event(StateTimeout, _) =>
      goto(Connected) using connect()
  }

  when(Connected) {
    case Event(ConsoleClient.RegisterRequest(user, password, nickname), data: ConnectedData) =>
      loginDetails = Some(LoginRequest(user, password))
      register(data.stub, user, password, nickname)
      stay
    case Event(ConsoleClient.LoginRequest(user, password), cd: ConnectedData) =>
      loginDetails = Some(LoginRequest(user, password))
      login(cd.stub, loginDetails.get)
      stay

    case Event(LoginRegisterResponse(Response.Success(LoginSuccess(token))), cd: ConnectedData) =>
      log.info("logged in with token: {}", token)
      val data = LoggedInData(cd.channel, cd.stub, token, cd.currentChannel)
      data.startChatting
      goto(LoggedIn) using data
    case Event(_: LoginRegisterResponse, data) =>
      log.warning("login/registration failed")
      goto(Disconnected) forMax 5.seconds using DisconnectedData(data.currentChannel)
    case Event(Failure(t), data) =>
      log.warning("login/registration failed: {}", t)
      goto(Disconnected) forMax 5.seconds using DisconnectedData(data.currentChannel)
  }

  when(LoggedIn) {
    case Event(ConsoleClient.JoinRequest(chName), d: LoggedInData) =>
      d.join(chName)
      stay() using d.copy(currentChannel = Some(chName))
    case Event(ConsoleClient.LeaveRequest(chName), d: LoggedInData) =>
      d.leave(chName)
      stay() using d.copy(currentChannel = None)
    case Event(ConsoleClient.PostMessageRequest(msg), d: LoggedInData) =>
      d.message(msg)
      stay()

    case Event(ListenEvent(channel, nickname, message, timestamp), _) =>
      println(s"[${timestampStr(timestamp)}][$channel] $nickname: $message")
      stay()

    case Event(ChatCompleted, _) =>
      log.info("chat stream completed")
      disconnect(stateData)
    case Event(ChatError(t), _) =>
      log.error(t, "chat stream error")
      disconnect(stateData)
  }

  whenUnhandled {
    case Event(CheckGrpcState, data: ConnectedData) =>
      checkChannel(data)
    case Event(CheckGrpcState, _) =>
      stay

    case Event(ConsoleClient.ChangeChannel(ch), data) =>
      stay using data.withCurrentChannel(Some(ch))

    case Event(c: ConsoleClient.NaggerCommand, data) =>
      log.info("received command {}, but not in the appropriate state (state: {}, data: {})", c, stateName, data)
      stash()
      stay
  }

  onTransition {
    case _ -> Disconnected =>
      cancelTimer(timerName)

    case _ -> Connected =>
      log.info("connected to server")
      (loginDetails, nextStateData) match {
        case (Some(details), cd: ConnectedData) =>
          login(cd.stub, details)
        case _ =>
      }

    case _ -> LoggedIn =>
      unstashAll()
  }

  onTermination {
    case _ =>
      disconnect(stateData)
  }

  initialize()

  private def connect(): ClientData = {
    val config = context.system.settings.config
    val host   = config.getString("nagger.server-host")
    val port   = config.getInt("nagger.server-port")

    log.info("connecting to {}:{}", host, port)
    val channel = ManagedChannelBuilder
      .forAddress(host, port)
      .usePlaintext(true)
      .build()
    val stub = NaggerGrpc.stub(channel)

    setTimer(timerName, CheckGrpcState, 1.second, repeat = true)
    LoggedOutData(channel, stub, None)
  }

  private def register(stub: NaggerStub, user: String, password: String, nickname: String): Unit = {
    log.info("registering as {}({})", nickname, user)
    import context.dispatcher

    stub
      .register(RegisterRequest(user, password, nickname))
      .pipeTo(self)
  }

  private def login(stub: NaggerStub, login: LoginRequest): Unit = {
    log.info("logging in as {}", login.username)
    import context.dispatcher

    stub
      .login(login)
      .pipeTo(self)
  }

  private def checkChannel(data: ConnectedData) = {
    if (data.isDisconnected)
      disconnect(data)
    else
      stay()
  }

  private def disconnect(data: ClientData) = {
    data.finish()

    goto(Disconnected)
      .forMax(5.seconds)
      .using(DisconnectedData(data.currentChannel))
  }
}
