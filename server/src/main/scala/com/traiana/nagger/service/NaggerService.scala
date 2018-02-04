package com.traiana.nagger.service

import akka.actor.ActorSystem
import akka.typed.scaladsl.AskPattern._
import akka.typed.scaladsl.adapter._
import com.google.protobuf.empty.Empty
import com.traiana.kit.boot.grpc.GrpcService
import com.traiana.kit.machinery.interop
import com.traiana.kit.machinery.interop.grpc.ErrorMapping
import com.traiana.nagger.akka.{ApiActor, ChannelManagerActor, LoginActor, UserDetailsActor}
import com.traiana.nagger.spb._
import io.grpc.ServerServiceDefinition
import io.grpc.stub.StreamObserver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.context.ApplicationContext

import scala.concurrent.Future

@GrpcService
class NaggerService(@Autowired spring: ApplicationContext) extends NaggerGrpc.Nagger with interop.grpc.Service {
  lazy val system        = ActorSystem("nagger")
  implicit val scheduler = system.scheduler

  system.registerOnTermination {
    SpringApplication.exit(spring)
  }

  val detailsActor = system.spawn(UserDetailsActor(), "user-details-actor")
  val loginActor   = system.spawn(LoginActor(detailsActor), "login-actor")
  val channelMgr   = system.spawn(ChannelManagerActor(), "channel-manager-actor")
  val apiActor     = system.spawn(ApiActor(loginActor, detailsActor, channelMgr), "api-actor")

  channelMgr ! ChannelManagerActor.setApiActor(apiActor)

  override implicit val knownErrors: ErrorMapping = ErrorMapping(
    "registration-failed" -> io.grpc.Status.ALREADY_EXISTS,
    "login-failed"        -> io.grpc.Status.PERMISSION_DENIED,
    "invalid-token"       -> io.grpc.Status.UNAUTHENTICATED,
  )

  override def register(req: RegisterRequest): Future[LoginRegisterResponse] = {
    val r = ApiActor.register(req)
    (apiActor ? r).recoverKnown
  }

  override def login(req: LoginRequest): Future[LoginRegisterResponse] = {
    val r = ApiActor.login(req)
    (apiActor ? r).recoverKnown
  }

  override def joinLeave(req: JoinLeaveRequest): Future[Empty] = {
    val r = ApiActor.joinLeave(req)
    (apiActor ? r).recoverKnown
  }

  override def sendMessage(req: MessageRequest): Future[Empty] = {
    val r = ApiActor.message(req)
    (apiActor ? r).recoverKnown
  }

  override def listen(req: ListenRequest, obs: StreamObserver[ListenEvent]): Unit = {
    val r = ApiActor.listen(req, obs)
    apiActor ! r
  }

  override def bindService(): ServerServiceDefinition =
    NaggerGrpc.bindService(this, ec)
}
