/*
 * Copyright 2010 Twitter, Inc.
 * Copyright 2010 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import scala.collection.mutable
import com.twitter.conversions.time._
import com.twitter.logging.Logger
import com.twitter.naggati._
import com.twitter.naggati.Stages._
import com.twitter.util.{Duration, Time}
import org.jboss.netty.buffer.{ChannelBuffer, ChannelBuffers}
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.group.ChannelGroup

object TextCodec {
  val MAX_PUT_BUFFER = 1024

  def decoder = new Codec(read, write)

  val read = readLine(true, "ISO-8859-1") { line =>
    if (line.endsWith(":")) {
      val segments = line.substring(0, line.length - 1).split(" ")
      readData(segments, new mutable.ListBuffer[Array[Byte]])
    } else {
      val segments = line.split(" ")
      emit(TextRequest(segments(0).toLowerCase, segments.drop(1).toList, Nil))
    }
  }

  private def readData(segments: Seq[String], items: mutable.ListBuffer[Array[Byte]]): Stage = readLine(true, "ISO-8859-1") { line =>
    if (line == "" || (items.size >= MAX_PUT_BUFFER)) {
      emit(TextRequest(segments(0).toLowerCase, segments.drop(1).toList, items.toList))
    } else {
      items += line.getBytes("UTF-8")
      readData(segments, items)
    }
  }

  val write: PartialFunction[Any, ChannelBuffer] = {
    case response: TextResponse =>
      response.toBuffer
  }
}

case class TextRequest(command: String, args: List[String], items: List[Array[Byte]])

object TextResponse {
  val NO_ITEM = ChannelBuffers.wrappedBuffer("*\n".getBytes)
  val COLON = ':'.toByte
  val LF = '\n'.toByte
}

abstract class TextResponse {
  def toBuffer: ChannelBuffer
}
case class ItemResponse(data: Option[Array[Byte]]) extends TextResponse {
  def toBuffer = {
    if (data.isDefined) {
      val bytes = data.get
      val buffer = ChannelBuffers.buffer(bytes.size + 2)
      buffer.writeByte(TextResponse.COLON)
      buffer.writeBytes(bytes)
      buffer.writeByte(TextResponse.LF)
      buffer
    } else {
      TextResponse.NO_ITEM
    }
  }
}
case class ErrorResponse(message: String) extends TextResponse {
  def toBuffer = ChannelBuffers.wrappedBuffer(("-" + message + "\n").getBytes("ascii"))
}
case class CountResponse(count: Long) extends TextResponse {
  def toBuffer = ChannelBuffers.wrappedBuffer(("+" + count.toString + "\n").getBytes("ascii"))
}

/**
 * Simple text-line protocol handler for a kestrel connection.
 */
class TextHandler(
  channel: Channel,
  channelGroup: ChannelGroup,
  queueCollection: QueueCollection,
  maxOpenTransactions: Int,
  clientTimeout: Duration)
extends NettyHandler[TextRequest](channel, channelGroup, queueCollection, maxOpenTransactions, clientTimeout) {
  protected final def handle(request: TextRequest) = {
    request.command match {
      case "put" =>
        // put <queue> [expiry]:
        if (request.args.size < 1) {
          channel.write(ErrorResponse("Queue name required."))
          throw new ProtocolError("Queue name required.")
        }
        val queueName = request.args(0)
        try {
          val expiry = request.args.drop(1).headOption.map { Time.now + _.toInt.milliseconds }
          var count = 0
          request.items.foreach { item =>
            if (setItem(queueName, 0, expiry, item)) count += 1
          }
          channel.write(CountResponse(count))
        } catch {
          case e: NumberFormatException =>
            channel.write(ErrorResponse("Error parsing expiration time."))
        }
      case "get" =>
        // get <queue> [timeout]
        if (request.args.size < 1) {
          channel.write(ErrorResponse("Queue name required."))
          throw new ProtocolError("Queue name required.")
        }
        val queueName = request.args(0)
        try {
          val timeout = request.args.drop(1).headOption.map { _.toInt }.getOrElse(0)
          closeAllTransactions(queueName)
          getItem(queueName, timeout, true, false) { item =>
            channel.write(ItemResponse(item.map { _.data }))
          }
        } catch {
          case e: NumberFormatException =>
            channel.write(ErrorResponse("Error parsing timeout."))
        }
      case "monitor" =>
        // monitor <queue> <timeout>
        if (request.args.size < 2) {
          channel.write(ErrorResponse("Queue name & timeout required."))
          throw new ProtocolError("Queue name & timeout required.")
        }
        val queueName = request.args(0)
        val timeout = request.args(1).toInt.milliseconds.fromNow
        closeAllTransactions(queueName)
        monitorUntil(queueName, timeout) { item =>
          channel.write(ItemResponse(item.map { _.data }))
        }
      case "quit" =>
        channel.close()
      case "shutdown" =>
        shutdown()
        channel.write(CountResponse(0))
      case x =>
        channel.write(ErrorResponse("Unknown command: " + x))
// peek <queue> [timeout]
// confirm <queue> <count>
    }
  }

  protected final def handleProtocolError() {
    channel.write(ErrorResponse("Protocol error."))
  }

  protected final def handleException(e: Throwable) {
    channel.write(ErrorResponse("Internal error."))
  }
}
