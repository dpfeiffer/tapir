package tapir.server.play

import akka.stream.Materializer
import play.api.libs.Files.{SingletonTemporaryFileCreator, TemporaryFileCreator}
import play.api.mvc._
import sttp.tapir.server.{ServerDefaults}

import scala.concurrent.ExecutionContext
import sttp.tapir.server.{LogRequestHandling, DecodeFailureHandler, ServerDefaults}
import play.api.Logger

case class PlayServerOptions(
    decodeFailureHandler: DecodeFailureHandler,
    logRequestHandling: LogRequestHandling[Logger => Unit],
    temporaryFileCreator: TemporaryFileCreator,
    defaultActionBuilder: ActionBuilder[Request, AnyContent],
    playBodyParsers: PlayBodyParsers
)

object PlayServerOptions {
  implicit def default(implicit mat: Materializer, ec: ExecutionContext): PlayServerOptions =
    PlayServerOptions(
      ServerDefaults.decodeFailureHandler,
      defaultLogRequestHandling,
      SingletonTemporaryFileCreator,
      DefaultActionBuilder.apply(PlayBodyParsers.apply().anyContent),
      PlayBodyParsers.apply()
    )

  lazy val defaultLogRequestHandling: LogRequestHandling[Logger => Unit] = LogRequestHandling(
    doLogWhenHandled = debugLog,
    doLogAllDecodeFailures = debugLog,
    doLogLogicExceptions = (msg: String, ex: Throwable) => log => log.error(msg, ex),
    noLog = _ => ()
  )

  private def debugLog(msg: String, exOpt: Option[Throwable]): Logger => Unit = exOpt match {
    case None     => log => log.debug(msg)
    case Some(ex) => log => log.debug(s"$msg; exception: {}", ex)
  }
}
