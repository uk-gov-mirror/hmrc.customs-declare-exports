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

package uk.gov.hmrc.exports.services

import java.time.{LocalDateTime, ZoneOffset, ZonedDateTime}

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, anyString, eq => meq}
import reactivemongo.bson.BSONObjectID
import testdata.ExportsDeclarationBuilder
import testdata.ExportsTestData._
import testdata.SubmissionTestData._
import uk.gov.hmrc.exports.base.UnitSpec
import uk.gov.hmrc.exports.connectors.CustomsDeclarationsConnector
import uk.gov.hmrc.exports.models.declaration.notifications.{NotificationDetails, ParsedNotification}
import uk.gov.hmrc.exports.models.declaration.submissions._
import uk.gov.hmrc.exports.models.declaration.{DeclarationStatus, ExportsDeclaration}
import uk.gov.hmrc.exports.repositories.{DeclarationRepository, NotificationRepository, SubmissionRepository}
import uk.gov.hmrc.exports.services.mapping.CancellationMetaDataBuilder
import uk.gov.hmrc.exports.services.notifications.receiptactions.SendEmailForDmsDocAction
import uk.gov.hmrc.http.HeaderCarrier
import wco.datamodel.wco.documentmetadata_dms._2.MetaData

import scala.concurrent.{ExecutionContext, Future}

class SubmissionServiceSpec extends UnitSpec with ExportsDeclarationBuilder {

  private implicit val hc: HeaderCarrier = mock[HeaderCarrier]
  private val customsDeclarationsConnector: CustomsDeclarationsConnector = mock[CustomsDeclarationsConnector]
  private val submissionRepository: SubmissionRepository = mock[SubmissionRepository]
  private val declarationRepository: DeclarationRepository = mock[DeclarationRepository]
  private val notificationRepository: NotificationRepository = mock[NotificationRepository]
  private val metaDataBuilder: CancellationMetaDataBuilder = mock[CancellationMetaDataBuilder]
  private val wcoMapperService: WcoMapperService = mock[WcoMapperService]
  private val sendEmailForDmsDocAction: SendEmailForDmsDocAction = mock[SendEmailForDmsDocAction]

  private val submissionService = new SubmissionService(
    customsDeclarationsConnector = customsDeclarationsConnector,
    submissionRepository = submissionRepository,
    declarationRepository = declarationRepository,
    notificationRepository = notificationRepository,
    metaDataBuilder = metaDataBuilder,
    wcoMapperService = wcoMapperService,
    sendEmailForDmsDocAction = sendEmailForDmsDocAction
  )(ExecutionContext.global)

  override def afterEach(): Unit = {
    reset(
      customsDeclarationsConnector,
      submissionRepository,
      declarationRepository,
      notificationRepository,
      metaDataBuilder,
      wcoMapperService,
      sendEmailForDmsDocAction
    )
    super.afterEach()
  }

  "SubmissionService on cancel" should {
    val submission = Submission("id", "eori", "lrn", None, "ducr")
    val submissionCancelled = Submission("id", "eori", "lrn", None, "ducr", Seq(Action("conv-id", CancellationRequest)))
    val cancellation = SubmissionCancellation("ref-id", "mrn", "description", "reason")

    "submit and delegate to repository" when {
      "submission exists" in {
        when(metaDataBuilder.buildRequest(any(), any(), any(), any(), any())).thenReturn(mock[MetaData])
        when(wcoMapperService.toXml(any())).thenReturn("xml")
        when(customsDeclarationsConnector.submitCancellation(any(), any())(any()))
          .thenReturn(Future.successful("conv-id"))
        when(submissionRepository.findSubmissionByMrn(any())).thenReturn(Future.successful(Some(submission)))
        when(submissionRepository.addAction(any[String](), any())).thenReturn(Future.successful(Some(submission)))

        submissionService.cancel("eori", cancellation).futureValue mustBe CancellationRequested
      }

      "submission is missing" in {
        when(metaDataBuilder.buildRequest(any(), any(), any(), any(), any())).thenReturn(mock[MetaData])
        when(wcoMapperService.toXml(any())).thenReturn("xml")
        when(customsDeclarationsConnector.submitCancellation(any(), any())(any()))
          .thenReturn(Future.successful("conv-id"))
        when(submissionRepository.findSubmissionByMrn(any())).thenReturn(Future.successful(None))

        submissionService.cancel("eori", cancellation).futureValue mustBe MissingDeclaration
      }

      "submission exists and previously cancelled" in {
        when(metaDataBuilder.buildRequest(any(), any(), any(), any(), any())).thenReturn(mock[MetaData])
        when(wcoMapperService.toXml(any())).thenReturn("xml")
        when(customsDeclarationsConnector.submitCancellation(any(), any())(any()))
          .thenReturn(Future.successful("conv-id"))
        when(submissionRepository.findSubmissionByMrn(any()))
          .thenReturn(Future.successful(Some(submissionCancelled)))

        submissionService.cancel("eori", cancellation).futureValue mustBe CancellationRequestExists
      }
    }
  }

  "SubmissionService on submit" should {
    def theActionAdded(): Action = {
      val captor: ArgumentCaptor[Action] = ArgumentCaptor.forClass(classOf[Action])
      verify(submissionRepository).addAction(any[Submission](), captor.capture())
      captor.getValue
    }

    def theDeclarationUpdated(index: Int = 0): ExportsDeclaration = {
      val captor: ArgumentCaptor[ExportsDeclaration] = ArgumentCaptor.forClass(classOf[ExportsDeclaration])
      verify(declarationRepository, atLeastOnce).update(captor.capture())
      captor.getAllValues.get(index)
    }

    def theSubmissionCreated(): Submission = {
      val captor: ArgumentCaptor[Submission] = ArgumentCaptor.forClass(classOf[Submission])
      verify(submissionRepository).findOrCreate(any(), any(), captor.capture())
      captor.getValue
    }

    "submit to the Dec API" when {
      val declaration = aDeclaration()

      val notification = ParsedNotification(
        id = BSONObjectID.generate,
        actionId = "id1",
        payload = "xml",
        details = NotificationDetails("mrn", ZonedDateTime.of(LocalDateTime.now(), ZoneOffset.UTC), SubmissionStatus.ACCEPTED, Seq.empty)
      )
      val submission = Submission(declaration, "lrn", "mrn")

      "declaration is valid" in {
        // Given
        when(wcoMapperService.produceMetaData(any())).thenReturn(mock[MetaData])
        when(wcoMapperService.declarationLrn(any())).thenReturn(Some("lrn"))
        when(wcoMapperService.declarationDucr(any())).thenReturn(Some("ducr"))
        when(wcoMapperService.toXml(any())).thenReturn("xml")
        when(declarationRepository.update(any())).thenReturn(Future.successful(Some(mock[ExportsDeclaration])))
        when(submissionRepository.findOrCreate(any(), any(), any())).thenReturn(Future.successful(mock[Submission]))
        when(customsDeclarationsConnector.submitDeclaration(any(), any())(any()))
          .thenReturn(Future.successful("conv-id"))
        when(submissionRepository.addAction(any[Submission](), any())).thenReturn(Future.successful(submission))
        when(notificationRepository.findNotificationsByActionId(anyString())).thenReturn(Future.successful(Seq.empty))

        // When
        submissionService.submit(declaration).futureValue mustBe submission

        // Then
        theSubmissionCreated() mustBe Submission(declaration, "lrn", "ducr")

        val action = theActionAdded()
        action.id mustBe "conv-id"
        action.requestType mustBe SubmissionRequest

        theDeclarationUpdated().status mustEqual DeclarationStatus.COMPLETE

        verify(submissionRepository, never).updateMrn(any[String], any[String])
        verify(sendEmailForDmsDocAction, never).execute(any[String])
      }

      "existing notification is available" in {
        // Given
        when(wcoMapperService.produceMetaData(any())).thenReturn(mock[MetaData])
        when(wcoMapperService.declarationLrn(any())).thenReturn(Some("lrn"))
        when(wcoMapperService.declarationDucr(any())).thenReturn(Some("ducr"))
        when(wcoMapperService.toXml(any())).thenReturn("xml")
        when(declarationRepository.update(any())).thenReturn(Future.successful(Some(mock[ExportsDeclaration])))
        when(submissionRepository.findOrCreate(any(), any(), any())).thenReturn(Future.successful(mock[Submission]))
        when(customsDeclarationsConnector.submitDeclaration(any(), any())(any()))
          .thenReturn(Future.successful("conv-id"))
        when(submissionRepository.addAction(any[Submission](), any())).thenReturn(Future.successful(mock[Submission]))
        when(notificationRepository.findNotificationsByActionId(anyString()))
          .thenReturn(Future.successful(Seq(notification)))
        when(submissionRepository.updateMrn(any(), any())).thenReturn(Future.successful(Some(submission)))

        // When
        submissionService.submit(declaration).futureValue mustBe submission

        // Then
        theSubmissionCreated() mustBe Submission(declaration, "lrn", "ducr")

        val action = theActionAdded()
        action.id mustBe "conv-id"
        action.requestType mustBe SubmissionRequest

        theDeclarationUpdated().status mustEqual DeclarationStatus.COMPLETE

        verify(submissionRepository).updateMrn(meq("conv-id"), meq("mrn"))
        verify(sendEmailForDmsDocAction).execute(meq("conv-id"))
      }
    }

    "revert declaration to draft" when {
      "submission to the Dec API fails" in {
        // Given
        when(wcoMapperService.produceMetaData(any())).thenReturn(mock[MetaData])
        when(wcoMapperService.declarationLrn(any())).thenReturn(Some("lrn"))
        when(wcoMapperService.declarationDucr(any())).thenReturn(Some("ducr"))
        when(wcoMapperService.toXml(any())).thenReturn("xml")
        when(declarationRepository.update(any())).thenReturn(Future.successful(Some(mock[ExportsDeclaration])))
        when(submissionRepository.findOrCreate(any(), any(), any())).thenReturn(Future.successful(mock[Submission]))
        when(customsDeclarationsConnector.submitDeclaration(any(), any())(any()))
          .thenReturn(Future.failed(new RuntimeException("Some error")))

        val declaration = aDeclaration()

        // When
        intercept[RuntimeException] {
          submissionService.submit(declaration).futureValue
        }

        // Then
        theSubmissionCreated() mustBe Submission(declaration, "lrn", "ducr")

        verify(submissionRepository, never).addAction(any[Submission], any[Action])

        theDeclarationUpdated(0).status mustEqual DeclarationStatus.COMPLETE
        theDeclarationUpdated(1).status mustEqual DeclarationStatus.DRAFT
      }
    }

    "throw exception" when {
      "missing LRN" in {
        when(wcoMapperService.produceMetaData(any())).thenReturn(mock[MetaData])
        when(wcoMapperService.declarationLrn(any())).thenReturn(None)
        when(wcoMapperService.declarationDucr(any())).thenReturn(Some("ducr"))

        intercept[IllegalArgumentException] {
          submissionService.submit(aDeclaration()).futureValue
        }
      }

      "missing DUCR" in {
        when(wcoMapperService.produceMetaData(any())).thenReturn(mock[MetaData])
        when(wcoMapperService.declarationLrn(any())).thenReturn(Some("lrn"))
        when(wcoMapperService.declarationDucr(any())).thenReturn(None)

        intercept[IllegalArgumentException] {
          submissionService.submit(aDeclaration()).futureValue
        }
      }
    }
  }

  "Get all Submissions for eori" should {
    "delegate to repository" in {
      val response = mock[Seq[Submission]]
      when(submissionRepository.findAllSubmissionsForEori(any())).thenReturn(Future.successful(response))

      submissionService.getAllSubmissionsForUser(eori).futureValue mustBe response

      verify(submissionRepository).findAllSubmissionsForEori(meq(eori))
    }
  }

  "Get Submission" should {
    "delegate to repository" in {
      val response = mock[Option[Submission]]
      when(submissionRepository.findSubmissionByUuid(any(), any())).thenReturn(Future.successful(response))

      submissionService.getSubmission(eori, uuid).futureValue mustBe response

      verify(submissionRepository).findSubmissionByUuid(meq(eori), meq(uuid))
    }
  }
}
