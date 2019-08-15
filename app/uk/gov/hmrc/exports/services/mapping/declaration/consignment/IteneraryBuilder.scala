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

package uk.gov.hmrc.exports.services.mapping.declaration.consignment

import javax.inject.Inject
import uk.gov.hmrc.exports.models.declaration.ExportsDeclaration
import uk.gov.hmrc.exports.services.mapping.ModifyingBuilder
import wco.datamodel.wco.dec_dms._2.Declaration
import wco.datamodel.wco.dec_dms._2.Declaration.Consignment.Itinerary
import wco.datamodel.wco.declaration_ds.dms._2.ItineraryRoutingCountryCodeType

import scala.collection.JavaConverters._

class IteneraryBuilder @Inject()() extends ModifyingBuilder[ExportsDeclaration, Declaration.Consignment] {

  def buildThenAdd(model: ExportsDeclaration, consignment: Declaration.Consignment): Unit = {
    val itineraries = model.locations.destinationCountries.map {
      _.countriesOfRouting.zipWithIndex.map {
        case (countryCode, idx) => createItenerary(idx, countryCode)
      }
    }.getOrElse(Seq.empty)
    consignment.getItinerary.addAll(itineraries.toList.asJava)
  }

  def createItenerary(index: Integer, country: String): Itinerary = {
    val itenerary = new Declaration.Consignment.Itinerary()
    itenerary.setSequenceNumeric(new java.math.BigDecimal(index))

    val countryCodeType = new ItineraryRoutingCountryCodeType()
    countryCodeType.setValue(country)
    itenerary.setRoutingCountryCode(countryCodeType)
    itenerary
  }
}
