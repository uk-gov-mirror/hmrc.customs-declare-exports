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

package uk.gov.hmrc.exports.migrations.changelogs.notification

import com.mongodb.client.MongoDatabase
import org.bson.Document
import org.joda.time.DateTime
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.{exists, not, eq => feq}
import org.mongodb.scala.model.Updates.{combine, set, unset}
import play.api.Logging
import play.api.libs.json.Json
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.exports.migrations.changelogs.notification.SplitNotificationsCollection.HashFields
import uk.gov.hmrc.exports.migrations.changelogs.{MigrationDefinition, MigrationInformation}
import uk.gov.hmrc.exports.models.declaration.notifications.UnparsedNotification
import uk.gov.hmrc.exports.models.declaration.notifications.UnparsedNotification.DbFormat.format
import uk.gov.hmrc.exports.repositories.WorkItemFormat
import uk.gov.hmrc.workitem.{ProcessingStatus, Succeeded, ToDo, WorkItem}

import java.util.UUID
import javax.inject.Singleton
import scala.collection.JavaConverters._
import scala.collection.immutable.HashMap

@Singleton
class SplitNotificationsCollection extends MigrationDefinition with Logging {

  override val migrationInformation: MigrationInformation =
    MigrationInformation(id = "CEDS-3081 Split 'notifications' collection", order = 2, author = "Maciej Rewera", runAlways = true)

  private val NotificationsCollectionName = "notifications"
  private val UnparsedNotificationsCollectionName = "unparsedNotifications"

  private def getNotificationsCollection(implicit db: MongoDatabase) = db.getCollection(NotificationsCollectionName)
  private def getUnparsedNotificationsCollection(implicit db: MongoDatabase) = db.getCollection(UnparsedNotificationsCollectionName)

  private val IndexId = "_id"
  private val Details = "details"
  private val Payload = "payload"
  private val UnparsedNotificationId = "unparsedNotificationId"

  implicit private val workItemFormat = WorkItemFormat.workItemMongoFormat[UnparsedNotification]()

  override def migrationFunction(db: MongoDatabase): Unit = {
    logger.info(s"Applying '${migrationInformation.id}' db migration...  ")
    implicit val mongoDb: MongoDatabase = db

    val documentsToMigrateQuery = not(exists(UnparsedNotificationId))
    val queryBatchSize = 10

    getDocumentsToMigrate(documentsToMigrateQuery, queryBatchSize).foldLeft(buildUnparsedNotificationsHashMap) {
      (migratedNotificationsMap, document) =>
        val newUnparsedNotification = buildUnparsedNotification(document)
        val newUnparsedNotificationPartialHash = HashFields(newUnparsedNotification).##

        if (migratedNotificationsMap.contains(newUnparsedNotificationPartialHash)) {
          if (!document.containsKey(Details)) {
            removeFromNotificationsCollection(document)
          } else {
//            getUnparsedNotificationsCollection.find()

            val unparsedNotificationId = migratedNotificationsMap(newUnparsedNotificationPartialHash).id
            updateInNotificationsCollection(document, unparsedNotificationId)
          }

          migratedNotificationsMap
        } else {
          if (!document.containsKey(Details)) {
            insertUnparsedNotification(newUnparsedNotification, ToDo)
            removeFromNotificationsCollection(document)
          } else {
            insertUnparsedNotification(newUnparsedNotification, Succeeded)
            updateInNotificationsCollection(document, newUnparsedNotification.id)
          }

          migratedNotificationsMap + (newUnparsedNotificationPartialHash -> newUnparsedNotification)
        }
    }

    removeRedundantIndexes()
    logger.info(s"Applying '${migrationInformation.id}' db migration... Done.")
  }

  private def removeRedundantIndexes()(implicit db: MongoDatabase): Unit = {
    val redundantIndexesToBeDeleted = Seq("detailsDocMissingIdx")
    logger.debug(s"Removing redundant indexes: [${redundantIndexesToBeDeleted.mkString(", ")}]")

    getNotificationsCollection.listIndexes().iterator().forEachRemaining { idx =>
      val indexName = idx.getString("name")
      if (redundantIndexesToBeDeleted.contains(indexName))
        getNotificationsCollection.dropIndex(indexName)
    }
  }

  private def buildUnparsedNotificationsHashMap(implicit db: MongoDatabase): HashMap[Int, UnparsedNotification] = {
    val queryBatchSize = 10

    asScalaIterator(getUnparsedNotificationsCollection.find().batchSize(queryBatchSize).iterator)
      .map(_.get("item", classOf[Document]))
      .map(buildUnparsedNotification)
      .foldLeft(HashMap.empty[Int, UnparsedNotification])(
        (mapAcc, unparsedNotification) => mapAcc + (HashFields(unparsedNotification).## -> unparsedNotification)
      )
  }

  private def getDocumentsToMigrate(query: Bson, queryBatchSize: Int)(implicit db: MongoDatabase): Iterator[Document] = asScalaIterator(
    getNotificationsCollection
      .find(query)
      .batchSize(queryBatchSize)
      .iterator
  )

  private def insertUnparsedNotification(newUnparsedNotification: UnparsedNotification, status: ProcessingStatus)(implicit db: MongoDatabase) = {
    val workItem = newWorkItem(DateTime.now(), status, newUnparsedNotification)
    val workItemDocument = Document.parse(Json.toJson(workItem).toString)

    getUnparsedNotificationsCollection.insertOne(workItemDocument)
  }

  private def removeFromNotificationsCollection(document: Document)(implicit db: MongoDatabase) = {
    val documentId = document.get(IndexId)
    val filter = feq(IndexId, documentId)
    logger.info(s"Removing: [filter: $filter]")

    getNotificationsCollection.deleteOne(filter)
  }

  private def updateInNotificationsCollection(document: Document, unparsedNotificationId: UUID)(implicit db: MongoDatabase) = {
    val documentId = document.get(IndexId)
    val filter = feq(IndexId, documentId)
    val update = combine(set(UnparsedNotificationId, unparsedNotificationId.toString), unset(Payload))
    logger.info(s"Updating: [filter: $filter] [update: $update]")

    getNotificationsCollection.updateOne(filter, update)
  }

  private def newWorkItem(now: DateTime, initialState: ProcessingStatus, item: UnparsedNotification) =
    WorkItem(id = BSONObjectID.generate, receivedAt = now, updatedAt = now, availableAt = now, status = initialState, failureCount = 0, item = item)

  private def buildUnparsedNotification(document: Document) =
    UnparsedNotification(actionId = document.getString("actionId"), payload = document.getString("payload"))
}

object SplitNotificationsCollection {

  case class HashFields(actionId: String, payload: String)
  object HashFields {
    def apply(unparsedNotification: UnparsedNotification): HashFields =
      HashFields(actionId = unparsedNotification.actionId, payload = unparsedNotification.payload)
  }
}
