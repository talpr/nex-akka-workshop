package com.traiana.nagger.akka

import com.traiana.kit.machinery.interop.validation._
import com.traiana.nagger.spb._
import com.wix.accord.Validator

object Validators {
  implicit val registerRequestValidator: Validator[RegisterRequest]   = alwaysValid
  implicit val loginRequestValidator: Validator[LoginRequest]         = alwaysValid
  implicit val joinLeaveRequestValidator: Validator[JoinLeaveRequest] = alwaysValid
  implicit val messageRequestValidator: Validator[MessageRequest]     = alwaysValid
  implicit val listenRequestValidator: Validator[ListenRequest]       = alwaysValid
}
