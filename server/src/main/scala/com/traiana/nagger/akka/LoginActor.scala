package com.traiana.nagger.akka

import java.util.UUID

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.Timeout
import com.traiana.nagger.{Nickname, Password, Token, User}

import scala.concurrent.duration._
import scala.util.Success

object LoginActor {
  def login(user: User, pw: Password): ActorRef[LoginResponse] => Login =
    Login(user, pw, _)

  def validateToken(token: Token): ActorRef[TokenResponse] => ValidateToken =
    ValidateToken(token, _)

  sealed trait Command
  final case class Login(user: User, password: Password, replyTo: ActorRef[LoginResponse]) extends Command
  final case class ValidateToken(token: Token, replyTo: ActorRef[TokenResponse])           extends Command

  sealed trait LoginResponse
  final case class LoginSuccess(token: Token) extends LoginResponse
  final case class LoginFailed(user: User)    extends LoginResponse

  sealed trait TokenResponse
  final case class TokenValid(token: Token, nickname: Nickname) extends TokenResponse
  final case class TokenInvalid(token: Token)                   extends TokenResponse

  private final case class HandleLoggedIn(nickname: Nickname, replyTo: ActorRef[LoginResponse]) extends Command
  private final case class HandleFailedLogIn(user: User, replyTo: ActorRef[LoginResponse])      extends Command

  private def resultToHandle(replyTo: ActorRef[LoginResponse])(res: UserDetailsActor.Result): Command = res match {
    case UserDetailsActor.Succeeded(u, nick) => HandleLoggedIn(nick, replyTo)
    case UserDetailsActor.Failed(u)          => HandleFailedLogIn(u, replyTo)
  }

  def apply(detailsActor: ActorRef[UserDetailsActor.Command]): Behavior[Command] =
    behavior(detailsActor, Map.empty)

  private def behavior(
    detailsActor: ActorRef[UserDetailsActor.Command],
    tokens: Map[Token, Nickname]
  ): Behavior[Command] = {
    def validateToken(r: ValidateToken): Behavior[Command] = {
      tokens.get(r.token) match {
        case Some(nick) => r.replyTo ! TokenValid(r.token, nick)
        case _          => r.replyTo ! TokenInvalid(r.token)
      }
      Behaviors.same
    }

    def login(r: Login)(ctx: ActorContext[Command]): Behavior[Command] = {
      implicit val timeout = Timeout(5.seconds)
      ctx.ask(detailsActor)(UserDetailsActor.login(r.user, r.password)) {
        case Success(UserDetailsActor.Succeeded(u, nick)) => HandleLoggedIn(nick, r.replyTo)
        case _                                            => HandleFailedLogIn(r.user, r.replyTo)
      }

      Behaviors.same
    }

    def handleLoggedIn(r: HandleLoggedIn): Behavior[Command] = {
      val token = newToken()
      val ts    = tokens + (token -> r.nickname)
      r.replyTo ! LoginSuccess(token)
      behavior(detailsActor, ts)
    }

    def handleFailed(r: HandleFailedLogIn): Behavior[Command] = {
      r.replyTo ! LoginFailed(r.user)
      Behaviors.same
    }

    Behaviors.immutable {
      case (ctx, r: Login)           => login(r)(ctx)
      case (_, r: HandleLoggedIn)    => handleLoggedIn(r)
      case (_, r: HandleFailedLogIn) => handleFailed(r)
      case (_, r: ValidateToken)     => validateToken(r)
    }
  }

  private def newToken(): Token = UUID.randomUUID().toString
}
