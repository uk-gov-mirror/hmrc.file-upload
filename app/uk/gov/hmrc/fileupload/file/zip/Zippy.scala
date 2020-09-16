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

package uk.gov.hmrc.fileupload.file.zip

import java.io.{BufferedOutputStream, ByteArrayOutputStream}
import java.util.UUID
import java.util.zip.ZipOutputStream

import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.ByteString
import cats.data.Xor
import play.api.Logger
import play.api.libs.iteratee.Enumerator
import play.api.libs.iteratee.streams.IterateeStreams
import uk.gov.hmrc.fileupload.file.zip.Utils.Bytes
import uk.gov.hmrc.fileupload.file.zip.ZipStream.{ZipFileInfo, ZipStreamEnumerator}
import uk.gov.hmrc.fileupload.read.envelope.Service.{FindEnvelopeNotFoundError, FindResult, FindServiceError}
import uk.gov.hmrc.fileupload.read.envelope.{Envelope, EnvelopeStatusClosed, EnvelopeStatusRouteRequested}
import uk.gov.hmrc.fileupload.{EnvelopeId, FileId}

import scala.concurrent.{ExecutionContext, Future}

object Zippy {


  type ZipResult = Xor[ZipEnvelopeError, Enumerator[Bytes]]
  sealed trait ZipEnvelopeError
  case object ZipEnvelopeNotFoundError extends ZipEnvelopeError
  case object EnvelopeNotRoutedYet extends ZipEnvelopeError
  case class ZipProcessingError(message: String) extends ZipEnvelopeError

  def zipEnvelope(getEnvelope: (EnvelopeId) => Future[FindResult],
                  retrieveS3File: (EnvelopeId, FileId) => Future[Source[ByteString, _]])
                 (envelopeId: EnvelopeId)
                 (implicit ec: ExecutionContext, mat: Materializer): Future[ZipResult] = {

    def sourceToEnumerator(source: Source[ByteString, _]) = {
      val byteStringToArrayOfBytes = Flow[ByteString].map(_.toArray)
      IterateeStreams.publisherToEnumerator(
        source.via(byteStringToArrayOfBytes).runWith(Sink.asPublisher(fanout = false))
      )
    }

    getEnvelope(envelopeId) map {
      case Xor.Right(envelopeWithFiles @ Envelope(_, _, EnvelopeStatusRouteRequested | EnvelopeStatusClosed, _, _, _, _, Some(files), _, _, _)) =>
        val zipFiles = files.collect {
          case f =>
            val fileName = f.name.getOrElse(UUID.randomUUID().toString)
            Logger.info(s"""zipEnvelope: envelopeId=${envelopeWithFiles._id} fileId=${f.fileId} fileRefId=${f.fileRefId} length=${f.length.getOrElse(-1)} uploadDate=${f.uploadDate.getOrElse("-")}""")
            ZipFileInfo(
              fileName, isDir = false, new java.util.Date(),
              Some(() => retrieveS3File(envelopeWithFiles._id, f.fileId).map { sourceToEnumerator })
            )
        }
        Xor.right(ZipStreamEnumerator(zipFiles))

      case Xor.Right(envelopeWithoutFiles @ Envelope(_, _, EnvelopeStatusRouteRequested | EnvelopeStatusClosed, _, _, _, _, None, _, _, _)) =>
        Logger.warn(s"Retrieving zipped envelope [$envelopeId]. Envelope was empty - returning empty ZIP file.")
        Xor.Right(emptyZip())

      case Xor.Right(envelopeWithWrongStatus: Envelope) =>
        Logger.warn(s"Retrieving zipped envelope [$envelopeId]. Envelope has wrong status [${envelopeWithWrongStatus.status}], returned error")
        Xor.left(EnvelopeNotRoutedYet)

      case Xor.Left(FindEnvelopeNotFoundError) =>
        Logger.warn(s"Retrieving zipped envelope [$envelopeId]. Envelope not found, returned error")
        Xor.left(ZipEnvelopeNotFoundError)

      case Xor.Left(FindServiceError(message)) =>
        Logger.warn(s"Retrieving zipped envelope [$envelopeId]. Other error [$message]")
        Xor.left(ZipProcessingError(message))
    }
  }

  def emptyZip(): Enumerator[Array[Byte]] = {
    val baos = new ByteArrayOutputStream()
    val out =  new ZipOutputStream(new BufferedOutputStream(baos))
    out.close()
    Enumerator(baos.toByteArray)
  }
}