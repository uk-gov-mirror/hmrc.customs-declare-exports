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

package uk.gov.hmrc.exports.models.declaration

import play.api.libs.json.{Json, OFormat}

case class EntityDetails(eori: Option[String], address: Option[Address])
object EntityDetails {
  implicit val format: OFormat[EntityDetails] = Json.format[EntityDetails]
}

case class Address(fullName: String, addressLine: String, townOrCity: String, postCode: String, country: String)
object Address {
  implicit val format: OFormat[Address] = Json.format[Address]
}

case class ExporterDetails(details: EntityDetails)
object ExporterDetails {
  implicit val format: OFormat[ExporterDetails] = Json.format[ExporterDetails]
}

case class ConsigneeDetails(details: EntityDetails)
object ConsigneeDetails {
  implicit val format: OFormat[ConsigneeDetails] = Json.format[ConsigneeDetails]
}

case class DeclarantDetails(details: EntityDetails)
object DeclarantDetails {
  implicit val format: OFormat[DeclarantDetails] = Json.format[DeclarantDetails]
}

case class RepresentativeDetails(details: Option[EntityDetails], statusCode: Option[String])
object RepresentativeDetails {
  implicit val format: OFormat[RepresentativeDetails] = Json.format[RepresentativeDetails]
}

case class DeclarationAdditionalActors(actors: Seq[DeclarationAdditionalActor])
object DeclarationAdditionalActors {
  implicit val format: OFormat[DeclarationAdditionalActors] = Json.format[DeclarationAdditionalActors]
}

case class DeclarationAdditionalActor(eori: Option[String], partyType: Option[String])
object DeclarationAdditionalActor {
  implicit val format: OFormat[DeclarationAdditionalActor] = Json.format[DeclarationAdditionalActor]
}

case class DeclarationHolders(holders: Seq[DeclarationHolder])
object DeclarationHolders {
  implicit val format: OFormat[DeclarationHolders] = Json.format[DeclarationHolders]
}

case class DeclarationHolder(authorisationTypeCode: Option[String], eori: Option[String])
object DeclarationHolder {
  implicit val format: OFormat[DeclarationHolder] = Json.format[DeclarationHolder]
}

case class CarrierDetails(details: EntityDetails)
object CarrierDetails {
  implicit val format: OFormat[CarrierDetails] = Json.format[CarrierDetails]
}

case class Parties(
  exporterDetails: Option[ExporterDetails] = None,
  consigneeDetails: Option[ConsigneeDetails] = None,
  declarantDetails: Option[DeclarantDetails] = None,
  representativeDetails: Option[RepresentativeDetails] = None,
  declarationAdditionalActorsData: Option[DeclarationAdditionalActors] = None,
  declarationHoldersData: Option[DeclarationHolders] = None,
  carrierDetails: Option[CarrierDetails] = None
)
object Parties {
  implicit val format: OFormat[Parties] = Json.format[Parties]
}
