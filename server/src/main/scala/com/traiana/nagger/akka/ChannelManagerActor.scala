package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, Props}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityTypeKey}
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
  final case class RegisterApiActor(apiActor: ActorRef[ApiActor.Command])                          extends Command
  final case object Stop                                                                           extends Command

  private[akka] final case class NotifyClients(
    clients: Set[Nickname],
    channel: Channel,
    who: Nickname,
    when: Instant,
    msg: String
  ) extends Command

  private val typeKey = EntityTypeKey[ChannelActor.Command]("ChannelActor")

  def apply(): Behavior[Command] =
    Behaviors.deferred { ctx =>
      val sharding = ClusterSharding(ctx.system)
      val region = sharding.spawn(
        behavior = ch => ChannelActor(ch, ctx.self),
        props = Props.empty,
        typeKey = typeKey,
        settings = ClusterShardingSettings(ctx.system),
        maxNumberOfShards = 10,
        handOffStopMessage = ChannelActor.Stop
      )

      behavior(sharding, Set.empty)
    }

  private def behavior(
    sharding: ClusterSharding,
    apiActors: Set[ActorRef[ApiActor.Command]]
  ): Behavior[Command] = {

    def join(r: Join)(implicit ctx: ActorContext[Command]): Behavior[Command] = {
      val ch = sharding.entityRefFor(typeKey, r.channel)
      ch ! ChannelActor.join(r.nick, r.replyTo)
      Behaviors.same
    }

    def leave(r: Leave)(implicit ctx: ActorContext[Command]): Behavior[Command] = {
      val ch = sharding.entityRefFor(typeKey, r.channel)
      ch ! ChannelActor.leave(r.nick, r.replyTo)
      Behaviors.same
    }

    def message(r: Message)(implicit ctx: ActorContext[Command]): Behavior[Command] = {
      val ch = sharding.entityRefFor(typeKey, r.channel)
      ch ! ChannelActor.message(r.nick, r.msg, r.replyTo)
      Behaviors.same
    }

    def notify(r: NotifyClients): Behavior[Command] = {
      apiActors.foreach { apiActor =>
        val m = ApiActor.notify(r.clients, r.channel, r.who, r.when, r.msg)
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
      case (_, r: RegisterApiActor) =>
        behavior(sharding, apiActors + r.apiActor)
      case (_, Stop) =>
        Behaviors.stopped
    }
  }
}
