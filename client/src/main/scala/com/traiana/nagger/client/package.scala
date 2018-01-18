package com.traiana.nagger

import com.google.protobuf.timestamp.Timestamp
import com.google.protobuf.util.Timestamps

package object client {
  def timestampStr(timestampOption: Option[Timestamp]): String = timestampOption match {
    case None => ""
    case Some(ts) =>
      val jts = Timestamp.toJavaProto(ts)
      Timestamps.toString(jts)
  }
}
