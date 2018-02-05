package com.traiana.nagger.akka

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors.{CommandHandler, Effect}
import com.traiana.nagger.{Nickname, Password, User}

object UserDetailsActor {
  def register(user: User, nick: Nickname, pw: Password): ActorRef[Result] => Command =
    ref => RegisterUser(UserDetails(user, nick, pw), ref)

  def login(user: User, pw: Password): ActorRef[Result] => Command =
    ref => LoginUser(user, pw, ref)

  case class UserDetails(
    user: User,
    nickname: Nickname,
    password: Password
  )

  sealed trait Command
  final case class RegisterUser(details: UserDetails, replyTo: ActorRef[Result])        extends Command
  final case class LoginUser(user: User, password: Password, replyTo: ActorRef[Result]) extends Command

  sealed trait Result
  final case class Succeeded(nickname: Nickname) extends Result
  final case class Failed(user: User)            extends Result

  final case class State(users: Map[User, UserDetails] = Map.empty)

  sealed trait Event
  final case class UserRegistered(det: UserDetails) extends Event

  def apply(): Behavior[Command] =
    PersistentBehaviors.immutable("user-details-actor", State(), commandHandler, eventHandler)

  private val commandHandler: CommandHandler[Command, Event, State] = {
    case (ctx, s, RegisterUser(det, to)) if s.users contains det.user =>
      Effect.none
        .andThen {
          ctx.log.info("failed to register user {}. already exists.", det.user)
          to ! Failed(det.user)
        }

    case (ctx, _, RegisterUser(det, to)) =>
      Effect
        .persist(UserRegistered(det))
        .andThen {
          ctx.log.info("registered new user {}", det.user)
          to ! Succeeded(det.nickname)
        }

    case (ctx, s, LoginUser(user, pw, to)) =>
      val resp = s.users.get(user).map(_.password) match {
        case Some(`pw`) =>
          ctx.log.info("user {} authenticated", user)
          Succeeded(s.users(user).nickname)
        case _          =>
          ctx.log.info("user {} failed authentication", user)
          Failed(user)
      }
      Effect.none
        .andThen(to ! resp)
  }

  private val eventHandler: (State, Event) => State = {
    case (s, UserRegistered(det)) => State(s.users + (det.user -> det))
  }
}
