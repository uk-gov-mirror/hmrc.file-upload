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

package uk.gov.hmrc.fileupload.testonly

import play.api.mvc.Action
import play.api.mvc.Results._
import reactivemongo.api.commands.WriteResult
import reactivemongo.bson.BSONDocument
import uk.gov.hmrc.fileupload.read.stats.{Repository => InProgressRepository}
import uk.gov.hmrc.fileupload.write.infrastructure.MongoEventStore

import scala.concurrent.{ExecutionContext, Future}

class TestOnlyController( dropCollections: () => Future[List[Unit]])
                        (implicit executionContext: ExecutionContext) {

  def dropAllCollections() = Action.async {
    request => dropCollections().map(r => Ok)

  }

}