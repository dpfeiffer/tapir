package tapir.server.play

import java.nio.charset.Charset

import akka.stream.Materializer
import akka.util.ByteString
import play.api.http.HttpEntity
import play.api.mvc._
import play.api.routing.Router.Routes
import sttp.tapir.internal.SeqToParams
import sttp.tapir.server.internal.{DecodeInputs, DecodeInputsResult, InputValues}
import sttp.tapir.server.ServerDefaults.StatusCodes
import sttp.tapir.server.{DecodeFailureHandling, ServerDefaults}
import sttp.tapir.{DecodeFailure, DecodeResult, Endpoint, EndpointIO, EndpointInput}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.reflect.ClassTag
import sttp.tapir.server.DecodeFailureContext
import play.api.Logger

trait TapirPlayServer {

  implicit class RichPlayServerEndpoint[I, E, O](e: Endpoint[I, E, O, Nothing]) {
    def toRoute(
        logic: I => Future[Either[E, O]]
    )(implicit mat: Materializer, serverOptions: PlayServerOptions): Routes = {
      def valuesToResponse(values: DecodeInputsResult.Values): Future[Result] = {
        val i = SeqToParams(InputValues(e.input, values)).asInstanceOf[I]
        logic(i)
          .map {
            case Right(result) => OutputToPlayResponse(ServerDefaults.StatusCodes.success, e.output, result)
            case Left(err)     => OutputToPlayResponse(ServerDefaults.StatusCodes.error, e.errorOutput, err)
          }
      }
      def handleDecodeFailure(
          e: Endpoint[_, _, _, _],
          req: RequestHeader,
          input: EndpointInput.Single[_],
          failure: DecodeFailure
      ): Result = {
        val decodeFailureCtx = DecodeFailureContext(input, failure)
        val handling = serverOptions.decodeFailureHandler(decodeFailureCtx)
        handling match {
          case DecodeFailureHandling.NoMatch =>
            serverOptions.logRequestHandling.decodeFailureNotHandled(e, decodeFailureCtx)(Logger("test"))
            Result(header = ResponseHeader(StatusCodes.error.code), body = HttpEntity.NoEntity)
          case DecodeFailureHandling.RespondWithResponse(output, value) =>
            serverOptions.logRequestHandling.decodeFailureNotHandled(e, decodeFailureCtx)(Logger("test"))
            // serverOptions.loggingOptions.decodeFailureHandledMsg(e, failure, input, value).foreach {
            //   case (msg, Some(t)) => println(s"$msg $t")
            //   case (msg, None)    => println(msg)
            // }

            OutputToPlayResponse(ServerDefaults.StatusCodes.error, output, value)
        }
      }

      def decodeBody(request: Request[RawBuffer], result: DecodeInputsResult)(implicit mat: Materializer): Future[DecodeInputsResult] = {
        result match {
          case values: DecodeInputsResult.Values =>
            values.bodyInput match {
              case Some(bodyInput @ EndpointIO.Body(codec, _)) =>
                new PlayRequestToRawBody(serverOptions)
                  .apply(
                    codec.meta.rawValueType,
                    request.charset.map(Charset.forName),
                    request,
                    request.body.asBytes().getOrElse(ByteString.apply(java.nio.file.Files.readAllBytes(request.body.asFile.toPath)))
                  )
                  .map { rawBody =>
                    val decodeResult = codec.decode(DecodeInputs.rawBodyValueToOption(rawBody, true))
                    decodeResult match {
                      case DecodeResult.Value(bodyV) => values.setBodyInputValue(bodyV)
                      case failure: DecodeFailure    => DecodeInputsResult.Failure(bodyInput, failure): DecodeInputsResult
                    }
                  }
              case None => Future(values)
            }
          case failure: DecodeInputsResult.Failure => Future(failure)
        }
      }

      val res = new PartialFunction[RequestHeader, Handler] {
        override def isDefinedAt(x: RequestHeader): Boolean = {
          val decodeInputResult = DecodeInputs(e.input, new PlayDecodeInputContext(x, 0, serverOptions))
          val handlingResult = decodeInputResult match {
            case DecodeInputsResult.Failure(input, failure) =>
              val decodeFailureCtx = DecodeFailureContext(input, failure)
              serverOptions.decodeFailureHandler(decodeFailureCtx) != DecodeFailureHandling.noMatch
            case DecodeInputsResult.Values(_, _) => true
          }
          handlingResult
        }

        override def apply(v1: RequestHeader): Handler = {
          serverOptions.defaultActionBuilder.async(serverOptions.playBodyParsers.raw) { request =>
            decodeBody(request, DecodeInputs(e.input, new PlayDecodeInputContext(v1, 0, serverOptions))).flatMap {
              case values: DecodeInputsResult.Values =>
                valuesToResponse(values)
              case DecodeInputsResult.Failure(input, failure) =>
                Future.successful(handleDecodeFailure(e, request, input, failure))
            }
          }
        }
      }
      res
    }

    def toRouteRecoverErrors(logic: I => Future[O])(
        implicit eIsThrowable: E <:< Throwable,
        eClassTag: ClassTag[E],
        mat: Materializer,
        serverOptions: PlayServerOptions
    ): Routes = {
      e.toRoute { i: I =>
        logic(i).map(Right(_)).recover {
          case ex if eClassTag.runtimeClass.isInstance(ex) => Left(ex.asInstanceOf[E])
        }
      }
    }
  }
}
