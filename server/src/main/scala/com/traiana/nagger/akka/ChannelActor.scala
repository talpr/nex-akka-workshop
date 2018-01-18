package com.traiana.nagger.akka

import java.time.Instant

import akka.Done
import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}
import com.traiana.nagger.{Channel, Nickname}

object ChannelActor {
  sealed trait Command

  final case class Join(nick: Nickname, replyTo: ActorRef[Done])                 extends Command
  final case class Leave(nick: Nickname, replyTo: ActorRef[Done])                extends Command
  final case class Message(nick: Nickname, msg: String, replyTo: ActorRef[Done]) extends Command

  def join(nick: Nickname, replyTo: ActorRef[Done]): Command                 = Join(nick, replyTo)
  def leave(nick: Nickname, replyTo: ActorRef[Done]): Command                = Leave(nick, replyTo)
  def message(nick: Nickname, msg: String, replyTo: ActorRef[Done]): Command = Message(nick, msg, replyTo)

  def apply(name: Channel, channelMgr: ActorRef[ChannelManagerActor.Command]): Behavior[Command] =
    behavior(name, channelMgr)

  private def behavior(
    channel: Channel,
    channelMgr: ActorRef[ChannelManagerActor.Command],
    joined: Set[Nickname] = Set.empty
  ): Behavior[Command] = {
    def join(r: Join): Behavior[Command] = {
      val j = joined + r.nick
      r.replyTo ! Done
      behavior(channel, channelMgr, j)
    }

    def leave(r: Leave): Behavior[Command] = {
      val j = joined - r.nick
      r.replyTo ! Done
      behavior(channel, channelMgr, j)
    }

    def message(r: Message): Behavior[Command] = {
      val m = ChannelManagerActor.notifyClients(joined, channel, r.nick, Instant.now(), r.msg)
      channelMgr ! m
      r.replyTo ! Done
      Actor.same
    }

    Actor.immutable {
      case (_, r: Join)    => join(r)
      case (_, r: Leave)   => leave(r)
      case (_, r: Message) => message(r)
    }
  }
}
