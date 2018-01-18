package com.traiana.nagger

import java.time.Instant

import com.google.protobuf.timestamp.Timestamp
import com.traiana.kit.machinery.interop.errors.ApiValidationError
import com.wix.accord

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

package object akka {

  /**
    * Validation pattern.
    *
    * Commonly used as
    * {{{
    *   validate(r).flatMap(r => ...)
    * }}}
    *
    * Returns the standard [[ApiValidationError]] when failed.
    */
  def validate[T: accord.Validator](t: T): Future[T] = {
    accord.validate(t) match {
      case accord.Success    => Future.successful(t)
      case f: accord.Failure => Future.failed(ApiValidationError(f))
    }
  }

  def timestampOf(i: Instant): Option[Timestamp] = {
    val ts = Timestamp().withSeconds(i.getEpochSecond).withNanos(i.getNano)
    Some(ts)
  }
}
