/*
 * Copyright 2016 HM Revenue & Customs
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

package uk.gov.hmrc.fileupload.envelope

import org.joda.time.DateTime
import play.api.libs.json.Json
import reactivemongo.api.commands.WriteResult
import reactivemongo.api.{DB, DBMetaCommands}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.fileupload.envelope.Service.UploadedFileInfo
import uk.gov.hmrc.fileupload.{EnvelopeId, FileId}
import uk.gov.hmrc.mongo.ReactiveRepository

import scala.concurrent.{ExecutionContext, Future}

object Repository {
  def apply(mongo: () => DB with DBMetaCommands): Repository = new Repository(mongo)
}

class Repository(mongo: () => DB with DBMetaCommands)
  extends ReactiveRepository[Envelope, BSONObjectID](collectionName = "envelopes", mongo, domainFormat = Envelope.envelopeFormat) {

  def update(envelope: Envelope)(implicit ec: ExecutionContext): Future[Boolean] = {
    collection.update(Json.obj(_Id -> envelope._id.value), envelope).map(toBoolean)
  }

  def updateFileStatus(envelopeId: EnvelopeId, fileId: FileId, fileStatus: FileStatus)
                      (implicit ec: ExecutionContext): Future[Boolean] = {
    val selector = Json.obj(_Id -> envelopeId.value, "files.fileId" -> fileId.value)
    val update = Json.obj("$set" -> Json.obj("files.$.status" -> fileStatus.name))
    collection.update(selector, update).map(toBoolean)
  }

  // TODO: WIP (konrad)
  def upsertFile(envelopeId: EnvelopeId, uploadedFileInfo: UploadedFileInfo)
                (implicit ec: ExecutionContext): Future[Boolean] = {
    val selector = Json.obj(_Id -> envelopeId.value, "files.fileId" -> uploadedFileInfo.fileId)
    val update = Json.obj("$set" -> Json.obj(
      "files.$.length"      -> uploadedFileInfo.length,
      "files.$.fsReference" -> uploadedFileInfo.fsReference,
      "files.$.uploadDate"  -> uploadedFileInfo.uploadDate.map(new DateTime(_))
    ))
    collection.update(selector, update, upsert = true).map(toBoolean)
  }

  def add(envelope: Envelope)(implicit ex: ExecutionContext): Future[Boolean] = {
    insert(envelope) map toBoolean
  }

  def get(id: EnvelopeId)(implicit ec: ExecutionContext): Future[Option[Envelope]] = {
    find("_id" -> id).map(_.headOption)
  }

  def delete(id: EnvelopeId)(implicit ec: ExecutionContext): Future[Boolean] = {
    remove("_id" -> id) map toBoolean
  }

  def toBoolean(wr: WriteResult): Boolean = wr match {
    case r if r.ok && r.n > 0 => true
    case _ => false
  }
}
