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

package uk.gov.hmrc.fileupload.controllers

import cats.data.Xor
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.libs.json.{JsValue, Json, Reads}
import play.api.mvc._
import uk.gov.hmrc.fileupload.ApplicationModule
import uk.gov.hmrc.fileupload.write.envelope.Formatters._
import uk.gov.hmrc.fileupload.write.envelope._
import uk.gov.hmrc.fileupload.write.infrastructure.{CommandAccepted, CommandNotAccepted}
import uk.gov.hmrc.play.bootstrap.controller.BackendController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CommandController @Inject()(
  appModule: ApplicationModule,
  cc: ControllerComponents
)(implicit executionContext: ExecutionContext
) extends BackendController(cc) {

  val handleCommand: (EnvelopeCommand) => Future[Xor[CommandNotAccepted, CommandAccepted.type]] = appModule.envelopeCommandHandler

  def unsealEnvelope = process[UnsealEnvelope]

  def storeFile = process[StoreFile]

  def quarantineFile = process[QuarantineFile]

  def markFileAsClean = process[MarkFileAsClean]

  def markFileAsInfected = process[MarkFileAsInfected]

  def process[T <: EnvelopeCommand : Reads : Manifest] = Action.async(parse.json) { implicit req =>
    bindCommandFromRequest[T] { command =>
      Logger.info(s"Requested command: $command to be processed")
      handleCommand(command).map {
        case Xor.Right(_) => Ok
        case Xor.Left(EnvelopeNotFoundError) =>
          ExceptionHandler(NOT_FOUND, s"Envelope with id: ${command.id} not found")
        case Xor.Left(FileAlreadyProcessed) =>
          ExceptionHandler(BAD_REQUEST, s"File already processed, command was: $command")
        case Xor.Left(EnvelopeRoutingAlreadyRequestedError | EnvelopeSealedError) =>
          ExceptionHandler(LOCKED, s"Routing request already received for envelope: ${command.id}")
        case Xor.Left(a) => ExceptionHandler(BAD_REQUEST, a.toString)
      }
    }
  }

  def bindCommandFromRequest[T <: EnvelopeCommand](f: EnvelopeCommand => Future[Result])
                                                  (implicit r: Reads[T], m: Manifest[T], req: Request[JsValue]) = {
    Json.fromJson[T](req.body).asOpt.map { command =>
      f(command)
    }.getOrElse {
      Future.successful(ExceptionHandler(BAD_REQUEST, s"Unable to parse request as ${m.runtimeClass.getSimpleName}"))
    }
  }

}
