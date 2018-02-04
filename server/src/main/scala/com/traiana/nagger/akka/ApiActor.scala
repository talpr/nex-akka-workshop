package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.actor.Scheduler
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{Actor, ActorContext}
import akka.actor.typed.{ActorRef, Behavior}
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

  def notify(to: Nickname, channel: Channel, sender: Nickname, when: Instant, message: String): Command =
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
    to: Nickname,
    channel: Channel,
    sender: Nickname,
    when: Instant,
    message: String
  ) extends Command

  private final case class Validated(nick: Nickname, obs: StreamObserver[ListenEvent]) extends Command

  case object RegistrationFailed extends ApiError("registration-failed")
  case object LoginFailed        extends ApiError("login-failed")
  case object InvalidToken       extends ApiError("invalid-token")

  def apply(
    loginActor: ActorRef[LoginActor.Command],
    detailsActor: ActorRef[UserDetailsActor.Command],
    channelManagerActor: ActorRef[ChannelManagerActor.Command]
  ): Behavior[Command] = behavior(loginActor, detailsActor, channelManagerActor)

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
        case Success(token) => replyTo ! succeeded(token)
        case Failure(t)     => replyTo ! failed(t.getMessage)
      }

      Actor.same
    }

    def login(req: LoginRequest, replyTo: ActorRef[LoginRegisterResponse])(
      implicit ctx: ActorContext[_]): Behavior[Command] = {
      implicit val ec = ctx.executionContext

      val f = for {
        LoginRequest(u, pw) <- validate(req)
        token               <- doLogin(u, pw)
      } yield token

      f.onComplete {
        case Success(token) => replyTo ! succeeded(token)
        case Failure(t)     => replyTo ! failed(t.getMessage)
      }

      Actor.same
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
      } yield Empty()
      f.foreach(replyTo.!)

      Actor.same
    }

    def message(req: MessageRequest, replyTo: ActorRef[Empty])(ctx: ActorContext[_]): Behavior[Command] = {
      implicit val ec = ctx.executionContext
      implicit val sc = ctx.system.scheduler

      val f = for {
        MessageRequest(tok, ch, msg) <- validate(req)
        nick                         <- checkToken(tok)
        m                            = ChannelManagerActor.message(nick, ch, msg)
        _                            <- channelManager ? m
      } yield Empty()
      f.foreach(replyTo.!)

      Actor.same
    }

    def listen(req: ListenRequest, obs: StreamObserver[ListenEvent])(ctx: ActorContext[Command]): Behavior[Command] = {
      implicit val ec = ctx.executionContext
      implicit val sc = ctx.system.scheduler

      val f = for {
        ListenRequest(tok) <- validate(req)
        nick               <- checkToken(tok)
      } yield Validated(nick, obs)
      f.foreach(ctx.self.!)

      Actor.same
    }

    def notify(r: Notify): Behavior[Command] = {
      listeners.get(r.to).foreach { l =>
        val le = ListenEvent(
          channel = r.channel,
          nickname = r.sender,
          message = r.message,
          timestamp = timestampOf(r.when)
        )
        l.onNext(le)
      }
      Actor.same
    }

    def validated(r: Validated): Behavior[Command] = {
      val ls = listeners + (r.nick -> r.obs)
      behavior(loginActor, detailsActor, channelManager, ls)
    }

    Actor.immutable {
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
      case (_, r: Notify) =>
        notify(r)
      case (_, r: Validated) =>
        validated(r)
    }
  }
}
