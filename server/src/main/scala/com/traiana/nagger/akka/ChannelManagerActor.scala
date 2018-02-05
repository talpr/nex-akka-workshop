package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.event.Logging
import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, Behavior}
import com.traiana.nagger.{Channel, Nickname}

object ChannelManagerActor {
  def joinLeave(nick: Nickname, channel: Channel, joinNotLeave: Boolean): ActorRef[Done] => Command = {
    if (joinNotLeave) join(nick, channel)
    else leave(nick, channel)
  }

  def join(nick: Nickname, channel: Channel): ActorRef[Done] => Command =
    Join(nick, channel, _)
  def leave(nick: Nickname, channel: Channel): ActorRef[Done] => Command =
    Leave(nick, channel, _)
  def message(nick: Nickname, channel: Channel, msg: String): ActorRef[Done] => Command =
    Message(nick, channel, msg, _)

  def notifyClients(
    clients: Set[Nickname],
    channel: Channel,
    who: Nickname,
    when: Instant,
    msg: String
  ): Command = NotifyClients(clients, channel, who, when, msg)

  sealed trait Command

  final case class Join(nick: Nickname, channel: Channel, replyTo: ActorRef[Done])                 extends Command
  final case class Leave(nick: Nickname, channel: Channel, replyTo: ActorRef[Done])                extends Command
  final case class Message(nick: Nickname, channel: Channel, msg: String, replyTo: ActorRef[Done]) extends Command

  private[akka] final case class NotifyClients(
    clients: Set[Nickname],
    channel: Channel,
    who: Nickname,
    when: Instant,
    msg: String
  ) extends Command

  def apply(apiActor: ActorRef[ApiActor.Command]): Behavior[Command] = behavior(apiActor)

  private def behavior(
    apiActor: ActorRef[ApiActor.Command],
    channels: Map[Channel, ActorRef[ChannelActor.Command]] = Map.empty
  ): Behavior[Command] = {

    def withChannel(channel: Channel)(f: ActorRef[ChannelActor.Command] => Unit)(
      implicit ctx: ActorContext[Command]
    ): Behavior[Command] = {
      channels.get(channel) match {
        case Some(ch) =>
          f(ch)
          Behaviors.same
        case None =>
          ctx.log.info("spawning new channel {}", channel)
          val ch    = ctx.spawn(ChannelActor(channel, ctx.self), s"channel-$channel")
          val chans = channels + (channel -> ch)
          f(ch)
          behavior(apiActor, chans)
      }
    }

    def join(r: Join)(implicit ctx: ActorContext[Command]): Behavior[Command] =
      withChannel(r.channel) { ch =>
        ch ! ChannelActor.join(r.nick, r.replyTo)
      }

    def leave(r: Leave)(implicit ctx: ActorContext[Command]): Behavior[Command] =
      withChannel(r.channel) { ch =>
        ch ! ChannelActor.leave(r.nick, r.replyTo)
      }

    def message(r: Message)(implicit ctx: ActorContext[Command]): Behavior[Command] =
      withChannel(r.channel) { ch =>
        ch ! ChannelActor.message(r.nick, r.msg, r.replyTo)
      }

    def notify(r: NotifyClients): Behavior[Command] = {
      r.clients.foreach { nick =>
        val m = ApiActor.notify(nick, r.channel, r.who, r.when, r.msg)
        apiActor ! m
      }

      Behaviors.same
    }

    Behaviors.immutable {
      case (ctx, r: Join) =>
        join(r)(ctx)
      case (ctx, r: Leave) =>
        leave(r)(ctx)
      case (ctx, r: Message) =>
        message(r)(ctx)
      case (_, r: NotifyClients) =>
        notify(r)
    }
  }
}
