package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.event.Logging
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.scaladsl.adapter._
import akka.typed.{ActorRef, Behavior}
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

  def setApiActor(apiActor: ActorRef[ApiActor.Command]): Command = SetApiActor(apiActor)
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

  private[akka] final case class SetApiActor(apiActor: ActorRef[ApiActor.Command]) extends Command
  private[akka] final case class NotifyClients(
    clients: Set[Nickname],
    channel: Channel,
    who: Nickname,
    when: Instant,
    msg: String
  ) extends Command

  def apply(): Behavior[Command] = uninitialized

  private val uninitialized: Behavior[Command] = {
    Actor.immutable {
      case (_, r: SetApiActor) =>
        behavior(r.apiActor)
      case (ctx, cmd) =>
        // BAD! shouldn't create log for each message, but this should be a rare case and i'm too lazy
        val log = Logging(ctx.system.toUntyped, ctx.self.toUntyped)
        log.warning("received message {} while uninitialized, ignoring", cmd)
        Actor.same
    }
  }

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
          Actor.same
        case None =>
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

      Actor.same
    }

    Actor.immutable {
      case (ctx, r: Join) =>
        join(r)(ctx)
      case (ctx, r: Leave) =>
        leave(r)(ctx)
      case (ctx, r: Message) =>
        message(r)(ctx)
      case (_, r: NotifyClients) =>
        notify(r)

      // ignore
      case (_, _: SetApiActor) =>
        Actor.same
    }
  }
}
