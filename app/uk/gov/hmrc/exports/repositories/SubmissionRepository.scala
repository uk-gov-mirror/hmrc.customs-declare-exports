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

package uk.gov.hmrc.exports.repositories

import java.util.UUID

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsObject, Json, OWrites, Reads}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor.FailOnError
import reactivemongo.api.FailoverStrategy.default
import reactivemongo.api.{QueryOpts, ReadConcern, ReadPreference}
import reactivemongo.api.commands.Command
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.{BSONBoolean, BSONDocument, BSONObjectID}
import reactivemongo.play.json.commands.JSONAggregationFramework
import reactivemongo.play.json.{ImplicitBSONHandlers, JSONSerializationPack}
import uk.gov.hmrc.exports.models.declaration.ExportsDeclaration
import uk.gov.hmrc.exports.models.declaration.notifications.Notification
import uk.gov.hmrc.exports.models.declaration.submissions.{Action, Submission}
import uk.gov.hmrc.exports.models.{DeclarationSort, Eori, Page, Paginated, SortDirection, SubmissionSearch}
import uk.gov.hmrc.exports.repositories.SubmissionRepository._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.objectIdFormats

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SubmissionRepository @Inject()(implicit mc: ReactiveMongoComponent, ec: ExecutionContext)
    extends ReactiveRepository[Submission, BSONObjectID]("submissions", mc.mongoConnector.db, Submission.formats, objectIdFormats) {

  override def indexes: Seq[Index] = Seq(
    Index(
      Seq("actions.id" -> IndexType.Ascending),
      unique = true,
      name = Some("actionIdIdx"),
      partialFilter = Some(BSONDocument(Seq("actions.id" -> BSONDocument("$exists" -> BSONBoolean(true)))))
    ),
    Index(Seq("eori" -> IndexType.Ascending), name = Some("eoriIdx")),
    Index(Seq("eori" -> IndexType.Ascending, "action.requestTimestamp" -> IndexType.Descending), name = Some("actionOrderedEori")),
    Index(Seq("updatedDateTime" -> IndexType.Ascending), name = Some("updateTimeIdx"))
  )

  def findAllSubmissionsForEori(eori: String): Future[Seq[Submission]] = {
    import reactivemongo.play.json.ImplicitBSONHandlers.JsObjectDocumentWriter

    collection
      .find(Json.obj("eori" -> eori), None)
      .sort(Json.obj("actions.requestTimestamp" -> -1))
      .cursor[Submission](ReadPreference.primaryPreferred)
      .collect(maxDocs = -1, FailOnError[Seq[Submission]]())
  }

  def findAllSubmissions(search: SubmissionSearch, pagination: Option[Page] = None, sort: Option[DeclarationSort] = None): Future[Seq[Submission]] = {
    def filterBySearch(submissions: List[SubmissionEnhanced]): Seq[SubmissionEnhanced] =
      submissions.filter { submissionEnhanced =>
        val notifications = submissionEnhanced.notifications.sorted.reverse
        submissionEnhanced.eori == search.eori && notifications.headOption.exists(n => search.submissionStatus.contains(n.status))
      }

    val lookupStage =
      Json.obj(s"$$lookup" -> Json.obj("from" -> "notifications", "localField" -> "mrn", "foreignField" -> "mrn", "as" -> "notifications"))
    val aggregateCommand = Json.obj("aggregate" -> "submissions", "pipeline" -> Json.arr(lookupStage), "cursor" -> Json.obj())

    val runner = Command.run(JSONSerializationPack, default)

    runner
      .apply(collection.db, runner.rawCommand(aggregateCommand)(ImplicitBSONHandlers.JsObjectDocumentWriter))
      .cursor[SubmissionEnhanced](ReadPreference.primaryPreferred)
      .collect(-1, FailOnError[List[SubmissionEnhanced]]())
      .map(filterBySearch(_).map(_.toSubmission))
  }

  def findAllSubmissions_2(search: SubmissionSearch, pagination: Page, sort: DeclarationSort): Future[Seq[Submission]] = {
    val matchOperator = JSONAggregationFramework.Match(Json.obj("eori" -> search.eori))

    val lookupOperator = JSONAggregationFramework.Lookup(from = "notifications", localField = "mrn", foreignField = "mrn", as = "notifications")

    val sortOperator = JSONAggregationFramework.Sort(sort.direction match {
      case SortDirection.ASC => JSONAggregationFramework.Ascending(sort.by.toString)
      case SortDirection.DES => JSONAggregationFramework.Descending(sort.by.toString)
    })

    for {
      results <- collection
        .aggregateWith() { _ =>
          (matchOperator, List(lookupOperator, sortOperator))
        }

      //        .sort(Json.obj(sort.by.toString -> sort.direction.id))
      //        .options(QueryOpts(skipN = (pagination.index - 1) * pagination.size, batchSizeN = pagination.size))
      //        .cursor[ExportsDeclaration](ReadPreference.primaryPreferred)
      //        .collect(maxDocs = pagination.size, FailOnError[List[ExportsDeclaration]]())
      //        .map(_.toSeq)
      total <- collection.count(Some(query), limit = Some(0), skip = 0, hint = None, readConcern = ReadConcern.Local)
    } yield {
      Paginated(currentPageElements = results, page = pagination, total = total)
    }
  }

  def findOrCreate(eori: Eori, id: String, onMissing: Submission): Future[Submission] =
    findSubmissionByUuid(eori.value, id).flatMap {
      case Some(submission) => Future.successful(submission)
      case None             => save(onMissing)
    }

  def findSubmissionByMrn(mrn: String): Future[Option[Submission]] = find("mrn" -> mrn).map(_.headOption)

  def findSubmissionByUuid(eori: String, uuid: String): Future[Option[Submission]] =
    find("eori" -> eori, "uuid" -> uuid).map(_.headOption)

  def save(submission: Submission): Future[Submission] = insert(submission).map { res =>
    if (!res.ok) logger.error(s"Errors when persisting declaration submission: ${res.writeErrors.mkString("--")}")
    submission
  }

  def updateMrn(conversationId: String, newMrn: String): Future[Option[Submission]] = {
    val query = Json.obj("actions.id" -> conversationId)
    val update = Json.obj("$set" -> Json.obj("mrn" -> newMrn))
    performUpdate(query, update)
  }

  def addAction(mrn: String, newAction: Action): Future[Option[Submission]] = {
    val query = Json.obj("mrn" -> mrn)
    val update = Json.obj("$addToSet" -> Json.obj("actions" -> newAction))
    performUpdate(query, update)
  }

  def addAction(submission: Submission, action: Action): Future[Submission] = {
    val query = Json.obj("uuid" -> submission.uuid)
    val update = Json.obj("$addToSet" -> Json.obj("actions" -> action))
    performUpdate(query, update).map(_.getOrElse(throw new IllegalStateException("Submission must exist before")))
  }

  private def performUpdate(query: JsObject, update: JsObject): Future[Option[Submission]] =
    findAndUpdate(query, update, fetchNewObject = true).map { updateResult =>
      if (updateResult.value.isEmpty) {
        updateResult.lastError.foreach(_.err.foreach(errorMsg => logger.error(s"Problem during database update: $errorMsg")))
      }
      updateResult.result[Submission]
    }
}

object SubmissionRepository {

  private case class SubmissionEnhanced(
    uuid: String = UUID.randomUUID().toString,
    eori: String,
    lrn: String,
    mrn: Option[String] = None,
    ducr: String,
    actions: Seq[Action] = Seq.empty,
    notifications: Seq[Notification] = Seq.empty
  ) {
    def toSubmission: Submission =
      Submission(uuid = this.uuid, eori = this.eori, lrn = this.lrn, mrn = this.mrn, ducr = this.ducr, actions = this.actions)
  }

  private object SubmissionEnhanced {
    implicit val reads: Reads[SubmissionEnhanced] = Json.reads[SubmissionEnhanced]
    implicit val writes: OWrites[SubmissionEnhanced] = Json.writes[SubmissionEnhanced]
  }
}
