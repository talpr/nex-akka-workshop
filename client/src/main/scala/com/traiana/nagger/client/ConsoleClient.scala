package com.traiana.nagger.client

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import scala.io.StdIn

object ConsoleClient {
  private val ChannelRegex  = """channel (\w+)""".r
  private val JoinRegex     = """join (\w+)""".r
  private val LeaveRegex    = """leave (\w+)""".r
  private val LoginRegex    = """login (\S+) (\S+)""".r
  private val RegisterRegex = """register (\S+) (\S+) (\w+)""".r
  private case class ConsoleMessage(s: String)

  def props(naggerClient: ActorRef): Props = Props(new ConsoleClient(naggerClient: ActorRef))

  sealed trait NaggerCommand
  final case class JoinRequest(channelName: String)             extends NaggerCommand
  final case class LeaveRequest(channelName: String)            extends NaggerCommand
  final case class ChangeChannel(channelName: String)           extends NaggerCommand
  final case class PostMessageRequest(msg: String)              extends NaggerCommand
  final case class LoginRequest(user: String, password: String) extends NaggerCommand
  final case class RegisterRequest(user: String, password: String, nickname: String)
}

class ConsoleClient(client: ActorRef) extends Actor with ActorLogging {
  import ConsoleClient._

  private val config   = context.system.settings.config.getConfig("nagger")
  private val user     = config.getString("username")
  private val password = config.getString("password")
  private val nickname = config.getString("nickname")

  override def receive: Receive = {
    case ConsoleMessage("exit") =>
      context.system.terminate()

    case ConsoleMessage(ChannelRegex(channel)) =>
      client ! ChangeChannel(channel)

    case ConsoleMessage(JoinRegex(channel)) =>
      client ! JoinRequest(channel)
    case ConsoleMessage(LeaveRegex(channel)) =>
      client ! LeaveRequest(channel)

    case ConsoleMessage("login") =>
      client ! LoginRequest(user, password)
    case ConsoleMessage(LoginRegex(user, password)) =>
      client ! LoginRequest(user, password)

    case ConsoleMessage("register") =>
      client ! RegisterRequest(user, password, nickname)
    case ConsoleMessage(RegisterRegex(user, password, nickname)) =>
      client ! RegisterRequest(user, password, nickname)

    case ConsoleMessage(msg: String) =>
      client ! PostMessageRequest(msg)
  }

  override def preStart(): Unit = {
    startConsoleThread()
  }

  private def startConsoleThread(): Unit = {
    val t = new Thread(() => {
      while (true) {
        self ! ConsoleMessage(StdIn.readLine())
      }
    }, "console-reader-thread")
    t.setDaemon(true)
    t.start()
  }
}
