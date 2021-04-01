/*
 * Copyright 2021 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.collection.JSONCollection
import uk.gov.hmrc.exports.models.declaration.notifications.UnparsedNotification
import uk.gov.hmrc.exports.models.declaration.notifications.UnparsedNotification.DbFormat.format
import uk.gov.hmrc.exports.repositories.SendEmailWorkItemRepository.WorkItemFormat
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats.objectIdFormats
import uk.gov.hmrc.workitem.{WorkItem, WorkItemFieldNames, WorkItemRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UnparsedNotificationWorkItemRepository @Inject()(configuration: Configuration, reactiveMongoComponent: ReactiveMongoComponent)
    extends WorkItemRepository[UnparsedNotification, BSONObjectID](
      collectionName = "sendEmailWorkItems",
      mongo = reactiveMongoComponent.mongoConnector.db,
      itemFormat = WorkItemFormat.workItemMongoFormat[UnparsedNotification],
      config = configuration.underlying
    ) {

  override lazy val collection: JSONCollection =
    mongo().collection[JSONCollection](collectionName, failoverStrategy = RepositorySettings.failoverStrategy)

  override def indexes: Seq[Index] = super.indexes ++ Seq(
    Index(key = Seq("sendEmailDetails.notificationId" -> IndexType.Ascending), name = Some("sendEmailDetailsNotificationIdIdx"), unique = true)
  )

  override def now: DateTime = DateTime.now

  override def workItemFields: WorkItemFieldNames = new WorkItemFieldNames {
    val receivedAt = "receivedAt"
    val updatedAt = "updatedAt"
    val availableAt = "availableAt"
    val status = "status"
    val id = "_id"
    val failureCount = "failureCount"
  }

  override def inProgressRetryAfterProperty: String = "workItem.sendEmail.retryAfterMillis"

  def pushNew(item: UnparsedNotification)(implicit ec: ExecutionContext): Future[WorkItem[UnparsedNotification]] = pushNew(item, now)

}


