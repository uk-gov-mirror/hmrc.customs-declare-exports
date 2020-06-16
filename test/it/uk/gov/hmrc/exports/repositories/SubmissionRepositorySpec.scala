/*
 * Copyright 2019 HM Revenue & Customs
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

package integration.uk.gov.hmrc.exports.repositories

import java.time.{LocalDateTime, ZoneId, ZonedDateTime}
import java.util.UUID

import com.codahale.metrics.SharedMetricRegistries
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{BeforeAndAfterEach, MustMatchers, OptionValues, WordSpec}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.Injector
import play.api.inject.guice.GuiceApplicationBuilder
import reactivemongo.core.errors.DatabaseException
import stubs.TestMongoDB
import uk.gov.hmrc.exports.models.{DeclarationSort, Eori, Page, SubmissionSearch}
import uk.gov.hmrc.exports.models.declaration.submissions.{Action, CancellationRequest, Submission, SubmissionRequest, SubmissionStatus}
import uk.gov.hmrc.exports.repositories.{NotificationRepository, SubmissionRepository}
import testdata.ExportsTestData._
import testdata.NotificationTestData
import testdata.NotificationTestData.notification
import testdata.SubmissionTestData._

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global

class SubmissionRepositorySpec
    extends WordSpec with BeforeAndAfterEach with ScalaFutures with MustMatchers with OptionValues with IntegrationPatience {

//  override def fakeApplication: Application = {
//    SharedMetricRegistries.clear()
//    GuiceApplicationBuilder()
//      .overrides(bind[Clock].to(clock))
//      .configure(mongoConfiguration)
//      .build()
//  }
//  private val repo = app.injector.instanceOf[SubmissionRepository]

  private val injector: Injector = {
    SharedMetricRegistries.clear()
    GuiceApplicationBuilder()
//      .configure(TestMongoDB.mongoConfiguration)
      .injector()
  }
  private val repo: SubmissionRepository = injector.instanceOf[SubmissionRepository]
  private val notificationRepo: NotificationRepository = injector.instanceOf[NotificationRepository]

  implicit val ec: ExecutionContext = global

  override def beforeEach(): Unit = {
    super.beforeEach()

    repo.removeAll().futureValue
    notificationRepo.removeAll().futureValue
  }

  override def afterEach(): Unit = {
    repo.removeAll().futureValue
    notificationRepo.removeAll().futureValue

    super.afterEach()
  }

  "Submission Repository on save" when {

    "the operation was successful" should {
      "return true" in {
        repo.save(submission).futureValue must be(submission)

        val submissionInDB = repo.findSubmissionByMrn(mrn).futureValue
        submissionInDB must be(defined)
      }
    }

    "trying to save Submission with the same conversationId twice" should {
      "throw DatabaseException" in {
        repo.save(submission).futureValue must be(submission)
        val secondSubmission = submission_2.copy(actions = submission.actions)

        val exc = repo.save(secondSubmission).failed.futureValue

        exc mustBe an[DatabaseException]
        exc.getMessage must include("E11000 duplicate key error collection: customs-declare-exports.submissions index: actionIdIdx dup key")
      }

      "result in having only the first Submission persisted" in {
        repo.save(submission).futureValue must be(submission)
        val secondSubmission = submission_2.copy(actions = submission.actions)

        repo.save(secondSubmission).failed.futureValue

        val submissionsInDB = repo.findAllSubmissionsForEori(eori).futureValue
        submissionsInDB.length must be(1)
        submissionsInDB.head must equal(submission)
      }
    }

    "allow save two submissions with empty actions" in {
      repo.save(emptySubmission_1).futureValue must be(emptySubmission_1)
      repo.save(emptySubmission_2).futureValue must be(emptySubmission_2)
      repo.findAllSubmissionsForEori(eori).futureValue must have length 2
    }
  }

  "Submission Repository on updateMrn" should {

    "return empty Option" when {
      "there is no Submission with given ConversationId" in {
        val newMrn = mrn_2
        repo.updateMrn(actionId, newMrn).futureValue mustNot be(defined)
      }
    }

    "return Submission updated" when {
      "there is a Submission containing Action with given ConversationId" in {
        repo.save(submission).futureValue
        val newMrn = mrn_2
        val expectedUpdatedSubmission = submission.copy(mrn = Some(newMrn))

        val updatedSubmission = repo.updateMrn(actionId, newMrn).futureValue

        updatedSubmission.value must equal(expectedUpdatedSubmission)
      }

      "new MRN is the same as the old one" in {
        repo.save(submission).futureValue

        val updatedSubmission = repo.updateMrn(actionId, mrn).futureValue

        updatedSubmission.value must equal(submission)
      }
    }
  }

  "Submission Repository on addAction" when {

    val action = Action(UUID.randomUUID().toString, SubmissionRequest)

    "there is no submission" should {
      "return failed future with IllegalStateException" in {
        an[IllegalStateException] mustBe thrownBy {
          Await.result(repo.addAction(submission, action), patienceConfig.timeout)
        }
      }
    }

    "there is submission" should {
      "add action at end of sequence" in {
        val savedSubmission = repo.save(submission).futureValue
        repo.addAction(savedSubmission, action).futureValue
        val result = repo.findSubmissionByUuid(savedSubmission.eori, savedSubmission.uuid).futureValue.value
        result.actions.map(_.id) must contain(action.id)
      }
    }

    "there is no Submission with given MRN" should {
      "return empty Option" in {
        val newAction = Action(actionId_2, CancellationRequest)
        repo.addAction(mrn, newAction).futureValue mustNot be(defined)
      }
    }

    "there is a Submission with given MRN" should {
      "return Submission updated" in {
        repo.save(submission).futureValue
        val newAction = Action(actionId_2, CancellationRequest)
        val expectedUpdatedSubmission = submission.copy(actions = submission.actions :+ newAction)

        val updatedSubmission = repo.addAction(mrn, newAction).futureValue

        updatedSubmission.value must equal(expectedUpdatedSubmission)
      }
    }
  }

  "Submission Repository on findOrCreate" when {
    "there is submission" should {
      "return existing submission" in {
        repo.save(submission_2).futureValue
        val result = repo.findOrCreate(Eori(submission_2.eori), submission_2.uuid, submission).futureValue
        result.actions mustEqual submission_2.actions
      }
    }
    "there no submission" should {
      "insert provided submission" in {
        val result = repo.findOrCreate(Eori(submission_2.eori), submission_2.uuid, submission).futureValue
        result.actions mustEqual submission.actions
      }
    }
  }

  "Submission Repository on findAllSubmissionsByEori" when {

    "there is no Submission associated with this EORI" should {
      "return empty List" in {
        repo.findAllSubmissionsForEori(eori).futureValue must equal(Seq.empty)
      }
    }

    "there is single Submission associated with this EORI" should {
      "return this Submission only" in {
        repo.save(submission).futureValue

        val retrievedSubmissions = repo.findAllSubmissionsForEori(eori).futureValue

        retrievedSubmissions.size must equal(1)
        retrievedSubmissions.headOption.value must equal(submission)
      }
    }

    "there are multiple Submissions associated with this EORI" should {
      "return all the Submissions" in {
        repo.save(submission).futureValue
        repo.save(submission_2).futureValue
        repo.save(submission_3).futureValue

        val retrievedSubmissions = repo.findAllSubmissionsForEori(eori).futureValue

        retrievedSubmissions.size must equal(3)
        retrievedSubmissions must contain(submission)
        retrievedSubmissions must contain(submission_2)
        retrievedSubmissions must contain(submission_3)
        retrievedSubmissions must contain inOrder (submission, submission_3, submission_2)
      }
    }
  }

  "Submission Repository on findAllSubmissions" should {

    "return no Submission" when {

      "there is no Submission matching query" in {

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))

        repo.findAllSubmissions(query).futureValue mustBe empty
      }

      "provided with no status" in {

        repo.save(submission)
        notificationRepo.save(notification)

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq.empty)

        repo.findAllSubmissions(query).futureValue mustBe empty
      }

      "there is Submission matching eori but not matching status" in {

        repo.save(submission)
        notificationRepo.save(notification.copy(status = SubmissionStatus.REJECTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))

        repo.findAllSubmissions(query).futureValue mustBe empty
      }

      "there is Submission matching status but not matching eori" in {

        repo.save(submission)
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = "GB1234567890", submissionStatus = Seq(SubmissionStatus.ACCEPTED))

        repo.findAllSubmissions(query).futureValue mustBe empty
      }
    }

    "return correct Submissions" when {

      "there is single Submission matching query" in {

        repo.save(submission)
        repo.save(submission_2)
        repo.save(submission_3)
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))

        val retrievedSubmissions = repo.findAllSubmissions(query).futureValue

        retrievedSubmissions.size mustBe 1
        retrievedSubmissions.head mustBe submission
      }

      "there are multiple Submissions matching query" in {

        repo.save(submission)
        repo.save(submission_2)
        repo.save(submission_3)
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_2, status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))

        val retrievedSubmissions = repo.findAllSubmissions(query).futureValue

        retrievedSubmissions.size mustBe 2
        retrievedSubmissions must contain(submission)
        retrievedSubmissions must contain(submission_2)
      }

      "provided with multiple statuses matching query" in {

        repo.save(submission)
        repo.save(submission_2)
        repo.save(submission_3)
        notificationRepo.save(notification.copy(status = SubmissionStatus.CUSTOMS_POSITION_GRANTED))
        notificationRepo.save(notification.copy(mrn = mrn_2, status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED, SubmissionStatus.CUSTOMS_POSITION_GRANTED))

        val retrievedSubmissions = repo.findAllSubmissions(query).futureValue

        retrievedSubmissions.size mustBe 2
        retrievedSubmissions must contain(submission)
        retrievedSubmissions must contain(submission_2)
      }

      "provided with status PENDING" in {

        repo.save(submission)
        repo.save(submission_2)
        repo.save(submission_3)
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.PENDING))

        val retrievedSubmissions = repo.findAllSubmissions(query).futureValue

        retrievedSubmissions.size mustBe 2
        retrievedSubmissions must contain(submission_2, submission_3)
      }
    }

    "return correct page of results" when {

      val instant1: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2020, 1, 1, 1, 1), ZoneId.of("UTC"))
      val instant2: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2020, 2, 1, 1, 1), ZoneId.of("UTC"))
      val instant3: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2020, 3, 1, 1, 1), ZoneId.of("UTC"))
      def action(timestamp: ZonedDateTime) = Action(requestType = SubmissionRequest, id = actionId, requestTimestamp = timestamp)

      "asked for page no. 1" in {

        repo.save(submission.copy(actions = Seq(action(instant1))))
        repo.save(submission_2.copy(actions = Seq(action(instant2))))
        repo.save(submission_3.copy(actions = Seq(action(instant3))))
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_2, status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_3, status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))
        val page = Page(index = 1, size = 2)

        val retrievedSubmissions = repo.findAllSubmissions(query, page).futureValue

        retrievedSubmissions.size mustBe 2
        retrievedSubmissions must contain(submission, submission_2)
      }

      "asked for page no. 2" in {

        repo.save(submission.copy(actions = Seq(action(instant1))))
        repo.save(submission_2.copy(actions = Seq(action(instant2))))
        repo.save(submission_3.copy(actions = Seq(action(instant3))))
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_2, status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_3, status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))
        val page = Page(index = 1, size = 2)

        val retrievedSubmissions = repo.findAllSubmissions(query, page).futureValue

        retrievedSubmissions.size mustBe 1
        retrievedSubmissions.head mustBe submission_3
      }
    }

    "return Submissions sorted" when {

      val instant1: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2020, 1, 1, 1, 1), ZoneId.of("UTC"))
      val instant2: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2020, 2, 1, 1, 1), ZoneId.of("UTC"))
      val instant3: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(2020, 3, 1, 1, 1), ZoneId.of("UTC"))
      def action(timestamp: ZonedDateTime) = Action(requestType = SubmissionRequest, id = actionId, requestTimestamp = timestamp)

      "provided with sort by latest action timestamp with ascending order" in {

        repo.save(submission.copy(actions = Seq(action(instant1))))
        repo.save(submission_2.copy(actions = Seq(action(instant2))))
        repo.save(submission_3.copy(actions = Seq(action(instant3))))
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_2, status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_3, status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))
        val page = Page(index = 1, size = 10)
        val sort = DeclarationSort()

        val retrievedSubmissions = repo.findAllSubmissions(query, page, sort).futureValue

        retrievedSubmissions.size mustBe 3
        retrievedSubmissions mustBe Seq(submission, submission_2, submission_3)
      }

      "provided with sort by latest action timestamp with descending order" in {

        repo.save(submission.copy(actions = Seq(action(instant1))))
        repo.save(submission_2.copy(actions = Seq(action(instant2))))
        repo.save(submission_3.copy(actions = Seq(action(instant3))))
        notificationRepo.save(notification.copy(status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_2, status = SubmissionStatus.ACCEPTED))
        notificationRepo.save(notification.copy(mrn = mrn_3, status = SubmissionStatus.ACCEPTED))

        val query = SubmissionSearch(eori = eori, submissionStatus = Seq(SubmissionStatus.ACCEPTED))
        val page = Page(index = 1, size = 10)

        val retrievedSubmissions = repo.findAllSubmissions(query, page, sort).futureValue

        retrievedSubmissions.size mustBe 3
        retrievedSubmissions mustBe Seq(submission_3, submission, submission)
      }
    }
  }

  "Submission Repository on findSubmissionByMrn" when {

    "there is no Submission with given MRN" should {
      "return empty Option" in {
        repo.findSubmissionByMrn(mrn).futureValue mustNot be(defined)
      }
    }

    "there is a Submission with given MRN" should {
      "return this Submission" in {
        repo.save(submission).futureValue

        val retrievedSubmission = repo.findSubmissionByMrn(mrn).futureValue

        retrievedSubmission.value must equal(submission)
      }
    }
  }

  "Submission Repository on findSubmissionByUuid" when {

    "no matching submission exists" should {
      "return None" in {
        repo.findSubmissionByUuid(eori, uuid).futureValue mustBe None
      }
    }

    "part matching submission exists" should {
      "return None" in {
        repo.save(submission).futureValue
        repo.findSubmissionByUuid("other", uuid).futureValue mustBe None
        repo.findSubmissionByUuid(eori, "other").futureValue mustBe None
      }
    }

    "matching submission exists" should {
      "return this Some" in {
        repo.save(submission).futureValue

        repo.findSubmissionByUuid(eori, uuid).futureValue mustBe Some(submission)
      }
    }
  }

}
