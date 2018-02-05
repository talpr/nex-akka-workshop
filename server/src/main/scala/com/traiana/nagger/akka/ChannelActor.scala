package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.persistence.typed.scaladsl.PersistentBehaviors.{CommandHandler, Effect}
import com.traiana.nagger.{Channel, Nickname}

object ChannelActor {
  sealed trait Command

  final case class Join(nick: Nickname, replyTo: ActorRef[Done])                 extends Command
  final case class Leave(nick: Nickname, replyTo: ActorRef[Done])                extends Command
  final case class Message(nick: Nickname, msg: String, replyTo: ActorRef[Done]) extends Command

  def join(nick: Nickname, replyTo: ActorRef[Done]): Command                 = Join(nick, replyTo)
  def leave(nick: Nickname, replyTo: ActorRef[Done]): Command                = Leave(nick, replyTo)
  def message(nick: Nickname, msg: String, replyTo: ActorRef[Done]): Command = Message(nick, msg, replyTo)

  final case class State(joined: Set[Nickname] = Set.empty, history: Vector[MessagePosted] = Vector.empty)

  sealed trait Event
  final case class UserJoined(nick: Nickname)                                extends Event
  final case class UserLeft(nick: Nickname)                                  extends Event
  final case class MessagePosted(nick: Nickname, msg: String, when: Instant) extends Event

  def apply(name: Channel, channelMgr: ActorRef[ChannelManagerActor.Command]): Behavior[Command] =
    PersistentBehaviors.immutable(s"channel-actor-$name", State(), commandHandler(name, channelMgr), eventHandler)

  private def commandHandler(
    channel: Channel,
    channelMgr: ActorRef[ChannelManagerActor.Command]): CommandHandler[Command, Event, State] = {
    case (ctx, s, Join(nick, to)) =>
      Effect
        .persist(UserJoined(nick))
        .andThen {
          ctx.log.info("user {} joined channel", nick)
          to ! Done
          s.history.foreach { mp =>
            val m = ChannelManagerActor.notifyClients(Set(nick), channel, mp.nick, mp.when, mp.msg)
            channelMgr ! m
          }
        }

    case (ctx, _, Leave(nick, to)) =>
      Effect
        .persist(UserLeft(nick))
        .andThen {
          ctx.log.info("user {} left channel", nick)
          to ! Done
        }

    case (ctx, s, Message(nick, msg, to)) =>
      val when = Instant.now()
      val m    = ChannelManagerActor.notifyClients(s.joined, channel, nick, when, msg)
      Effect
        .persist(MessagePosted(nick, msg, when))
        .andThen {
          ctx.log.info("user {} posted message", nick)
          channelMgr ! m
          to ! Done
        }
  }

  private val eventHandler: (State, Event) => State = {
    case (s, UserJoined(nick)) => s.copy(joined = s.joined + nick)
    case (s, UserLeft(nick))   => s.copy(joined = s.joined - nick)
    case (s, m: MessagePosted) => s.copy(history = s.history :+ m)
  }
}
