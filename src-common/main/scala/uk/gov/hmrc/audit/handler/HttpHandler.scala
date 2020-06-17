/*
 * Copyright 2020 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.audit.handler

import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeoutException

import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.JsValue
import play.api.libs.ws.ahc.{AhcWSClientConfigFactory, StandaloneAhcWSClient}
import play.api.libs.ws.WSClientConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration


sealed trait HttpResult
object HttpResult {
  case class Response(statusCode: Int) extends HttpResult
  case object Malformed extends HttpResult
  case class Failure(msg: String, nested: Option[Throwable] = None) extends Exception(msg, nested.orNull) with HttpResult
}

abstract class HttpHandler(
  endpointUrl      : URL,
  userAgent        : String,
  connectTimeout   : Duration,
  requestTimeout   : Duration
) {

  private val logger: Logger = LoggerFactory.getLogger(getClass)
  val HTTP_STATUS_CONTINUE = 100

  // Create Akka system for thread and streaming management
  val wsClient = {
    implicit val system = akka.actor.ActorSystem()
    implicit val materializer = akka.stream.ActorMaterializer()

    StandaloneAhcWSClient(
      config = AhcWSClientConfigFactory.forConfig()
                 .copy(wsClientConfig = WSClientConfig()
                   .copy(
                     connectionTimeout = connectTimeout,
                     requestTimeout    = requestTimeout,
                     userAgent         = Some(userAgent)
                   )
                 )
    )
  }

  def sendHttpRequest(event: JsValue)(implicit ec: ExecutionContext): Future[HttpResult] =
    try {
      logger.debug(s"Sending audit request to URL ${endpointUrl.toString}")

      wsClient.url(endpointUrl.toString)
        .post(event)
        .map { response =>
          val httpStatusCode = response.status
          logger.debug(s"Got status code : $httpStatusCode")
          response.body
          logger.debug("Response processed and closed")

          if (httpStatusCode >= HTTP_STATUS_CONTINUE) {
            logger.info(s"Got status code $httpStatusCode from HTTP server.")
            HttpResult.Response(httpStatusCode)
          }
          else {
            logger.warn(s"Malformed response (status $httpStatusCode) returned from server")
            HttpResult.Malformed
          }
        }.recover {
          case e: java.util.concurrent.TimeoutException =>
            HttpResult.Failure("Error opening connection, or request timed out", Some(e))
          case e: IOException =>
            HttpResult.Failure("Error opening connection, or request timed out", Some(e))
        }
    } catch {
      case t: Throwable =>
        Future.successful(HttpResult.Failure("Error sending HTTP request", Some(t)))
    }
}
