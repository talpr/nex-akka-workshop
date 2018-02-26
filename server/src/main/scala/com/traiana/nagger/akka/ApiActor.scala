package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.cluster.typed.{ClusterSingleton, ClusterSingletonSettings}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.util.Timeout
import com.google.protobuf.empty.Empty
import Validators._
import com.traiana.kit.machinery.interop.errors.ApiError
import com.traiana.nagger._
import com.traiana.nagger.spb._
import io.grpc.stub.StreamObserver

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object ApiActor {
  def register(r: RegisterRequest): ActorRef[LoginRegisterResponse] => Command = Register(r, _)
  def login(r: LoginRequest): ActorRef[LoginRegisterResponse] => Command       = Login(r, _)
  def joinLeave(r: JoinLeaveRequest): ActorRef[Empty] => Command               = JoinLeave(r, _)
  def message(r: MessageRequest): ActorRef[Empty] => Command                   = Message(r, _)
  def listen(r: ListenRequest, o: StreamObserver[ListenEvent]): Command        = Listen(r, o)

  def notify(to: Set[Nickname], channel: Channel, sender: Nickname, when: Instant, message: String): Command =
    Notify(to, channel, sender, when, message)

  private def succeeded(token: Token): LoginRegisterResponse =
    LoginRegisterResponse().withSuccess(LoginSuccess(token))
  private def failed(msg: String): LoginRegisterResponse =
    LoginRegisterResponse().withFailure(LoginFailure(msg))

  sealed trait Command

  final case class Register(r: RegisterRequest, replyTo: ActorRef[LoginRegisterResponse]) extends Command
  final case class Login(r: LoginRequest, replyTo: ActorRef[LoginRegisterResponse])       extends Command
  final case class JoinLeave(r: JoinLeaveRequest, replyTo: ActorRef[Empty])               extends Command
  final case class Message(r: MessageRequest, replyTo: ActorRef[Empty])                   extends Command

  final case class Listen(r: ListenRequest, o: StreamObserver[ListenEvent]) extends Command

  final case class Notify(
    to: Set[Nickname],
    channel: Channel,
    sender: Nickname,
    when: Instant,
    message: String
  ) extends Command

  private final case class Validated(nick: Nickname, obs: StreamObserver[ListenEvent]) extends Command

  case object RegistrationFailed extends ApiError("registration-failed")
  case object LoginFailed        extends ApiError("login-failed")
  case object InvalidToken       extends ApiError("invalid-token")

  def apply(): Behavior[Command] =
    Behaviors.setup { ctx =>
      val singletonManager = ClusterSingleton(ctx.system)

      val detailsActor = singletonManager.spawn(
        UserDetailsActor(),
        "user-details-actor",
        Props.empty,
        ClusterSingletonSettings(ctx.system),
        UserDetailsActor.Stop
      )

      val channelMgr = singletonManager.spawn(
        ChannelManagerActor(),
        "channel-manager-actor",
        Props.empty,
        ClusterSingletonSettings(ctx.system),
        ChannelManagerActor.Stop
      )
      channelMgr ! ChannelManagerActor.RegisterApiActor(ctx.self)

      val loginActor = ctx.spawn(LoginActor(detailsActor), "login-actor")

      val sharding = ClusterSharding(ctx.system)
      val region = sharding.spawn(
        behavior = ch => ChannelActor(ch, channelMgr),
        props = Props.empty,
        typeKey = ChannelActor.shardingTypeKey,
        settings = ClusterShardingSettings(ctx.system),
        maxNumberOfShards = 10,
        handOffStopMessage = ChannelActor.Stop
      )

      behavior(loginActor, detailsActor, channelMgr)
    }

  private def behavior(
    loginActor: ActorRef[LoginActor.Command],
    detailsActor: ActorRef[UserDetailsActor.Command],
    channelManager: ActorRef[ChannelManagerActor.Command],
    listeners: Map[Nickname, StreamObserver[ListenEvent]] = Map.empty
  ): Behavior[Command] = {
    implicit val timeout = Timeout(5 seconds)

    def doRegister(user: User, pw: Password, nickname: Nickname)(implicit ctx: ActorContext[_]): Future[Done] = {
      implicit val sc = ctx.system.scheduler
      implicit val ec = ctx.executionContext

      val req = UserDetailsActor.register(user, nickname, pw)
      (detailsActor ? req).transform {
        case Success(_: UserDetailsActor.Succeeded) => Success(Done)
        case _                                      => Failure(RegistrationFailed)
      }
    }

    def doLogin(user: User, pw: Password)(implicit ctx: ActorContext[_]): Future[Token] = {
      implicit val sc = ctx.system.scheduler
      implicit val ec = ctx.executionContext

      val req = LoginActor.login(user, pw)
      (loginActor ? req).transform {
        case Success(LoginActor.LoginSuccess(tok)) => Success(tok)
        case _                                     => Failure(LoginFailed)
      }
    }

    def checkToken(token: Token)(implicit ec: ExecutionContext, sc: Scheduler): Future[Nickname] = {
      val req = LoginActor.validateToken(token)
      (loginActor ? req).transform {
        case Success(LoginActor.TokenValid(_, nick)) => Success(nick)
        case _                                       => Failure(InvalidToken)
      }
    }

    def register(req: RegisterRequest, replyTo: ActorRef[LoginRegisterResponse])(
      implicit ctx: ActorContext[_]): Behavior[Command] = {
      implicit val ec = ctx.executionContext

      val f = for {
        RegisterRequest(u, pw, n) <- validate(req)
        _                         <- doRegister(u, pw, n)
        token                     <- doLogin(u, pw)
      } yield token

      f.onComplete {
        case Success(token) =>
          ctx.log.info("{} registered successfully", req.username)
          replyTo ! succeeded(token)
        case Failure(t) =>
          ctx.log.info("{} failed to register", req.username)
          replyTo ! failed(t.getMessage)
      }

      Behaviors.same
    }

    def login(req: LoginRequest, replyTo: ActorRef[LoginRegisterResponse])(
      implicit ctx: ActorContext[_]): Behavior[Command] = {
      implicit val ec = ctx.executionContext

      val f = for {
        LoginRequest(u, pw) <- validate(req)
        token               <- doLogin(u, pw)
      } yield token

      f.onComplete {
        case Success(token) =>
          ctx.log.info("{} logged in successfully", req.username)
          replyTo ! succeeded(token)
        case Failure(t) =>
          ctx.log.info("{} failed to log in", req.username)
          replyTo ! failed(t.getMessage)
      }

      Behaviors.same
    }

    def joinLeave(req: JoinLeaveRequest, replyTo: ActorRef[Empty])(ctx: ActorContext[_]): Behavior[Command] = {
      implicit val ec = ctx.executionContext
      implicit val sc = ctx.system.scheduler

      // todo
      // bad tokens and validation errors will just timeout... bleargh
      val f = for {
        JoinLeaveRequest(tok, ch, jnl) <- validate(req)
        nick                           <- checkToken(tok)
        m                              = ChannelManagerActor.joinLeave(nick, ch, jnl)
        _                              <- channelManager ? m
      } yield nick

      f foreach { nick =>
        ctx.log.info("{} joined/left channel {}", nick, req.channel)
        replyTo ! Empty()
      }

      Behaviors.same
    }

    def message(req: MessageRequest, replyTo: ActorRef[Empty])(ctx: ActorContext[_]): Behavior[Command] = {
      implicit val ec = ctx.executionContext
      implicit val sc = ctx.system.scheduler

      val f = for {
        MessageRequest(tok, ch, msg) <- validate(req)
        nick                         <- checkToken(tok)
        m                            = ChannelManagerActor.message(nick, ch, msg)
        _                            <- channelManager ? m
      } yield nick

      f foreach { nick =>
        ctx.log.info("posted message by {} to channel {}", nick, req.channel)
        replyTo ! Empty()
      }

      Behaviors.same
    }

    def listen(req: ListenRequest, obs: StreamObserver[ListenEvent])(ctx: ActorContext[Command]): Behavior[Command] = {
      implicit val ec = ctx.executionContext
      implicit val sc = ctx.system.scheduler

      val f = for {
        ListenRequest(tok) <- validate(req)
        nick               <- checkToken(tok)
      } yield Validated(nick, obs)

      f foreach { m =>
        ctx.log.info("validated token for {}, starting to listen", m.nick)
        ctx.self ! m
      }

      Behaviors.same
    }

    def notify(r: Notify)(ctx: ActorContext[Command]): Behavior[Command] = {
      r.to.flatMap(listeners.get).foreach { l =>
        val le = ListenEvent(
          channel = r.channel,
          nickname = r.sender,
          message = r.message,
          timestamp = timestampOf(r.when)
        )
        l.onNext(le)
      }

      Behaviors.same
    }

    def validated(r: Validated)(ctx: ActorContext[Command]): Behavior[Command] = {
      ctx.log.info("validated token for {}", r.nick)
      val ls = listeners + (r.nick -> r.obs)
      behavior(loginActor, detailsActor, channelManager, ls)
    }

    Behaviors.immutable {
      // api commands
      case (ctx, Register(r, to)) =>
        register(r, to)(ctx)
      case (ctx, Login(r, to)) =>
        login(r, to)(ctx)
      case (ctx, JoinLeave(r, to)) =>
        joinLeave(r, to)(ctx)
      case (ctx, Message(r, to)) =>
        message(r, to)(ctx)
      case (ctx, Listen(r, o)) =>
        listen(r, o)(ctx)

      // internal commands
      case (ctx, r: Notify) =>
        notify(r)(ctx)
      case (ctx, r: Validated) =>
        validated(r)(ctx)
    }
  }
}
