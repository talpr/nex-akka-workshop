package com.traiana.nagger.akka

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}
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
  final case class Succeeded(user: User, nickname: Nickname) extends Result
  final case class Failed(user: User)                        extends Result

  def apply(): Behavior[Command] = behavior(Map.empty)

  private def behavior(users: Map[User, UserDetails]): Behavior[Command] =
    Actor.immutable {
      case (_, RegisterUser(det, to)) if users contains det.user =>
        to ! Failed(det.user)
        Actor.same

      case (_, RegisterUser(det, to)) =>
        to ! Succeeded(det.user, det.nickname)
        val updated = users + (det.user -> det)
        behavior(updated)

      case (_, LoginUser(user, pw, to)) =>
        users.get(user).map(_.password) match {
          case Some(`pw`) =>
            to ! Succeeded(user, users(user).nickname)
          case _ =>
            to ! Failed(user)
        }
        Actor.same
    }
}
