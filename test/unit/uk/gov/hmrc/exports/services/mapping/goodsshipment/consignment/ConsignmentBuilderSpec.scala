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

package unit.uk.gov.hmrc.exports.services.mapping.goodsshipment.consignment

import org.mockito.ArgumentMatchers.{any, refEq}
import org.mockito.Mockito.verify
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpec}
import uk.gov.hmrc.exports.models.Choice
import uk.gov.hmrc.exports.models.declaration._
import uk.gov.hmrc.exports.services.mapping.goodsshipment.consignment._
import util.testdata.ExportsDeclarationBuilder
import wco.datamodel.wco.dec_dms._2.Declaration
import wco.datamodel.wco.dec_dms._2.Declaration.GoodsShipment

class ConsignmentBuilderSpec extends WordSpec with Matchers with ExportsDeclarationBuilder with MockitoSugar {

  private val mockContainerCodeBuilder = mock[ContainerCodeBuilder]
  private val mockGoodsLocationBuilder = mock[GoodsLocationBuilder]
  private val mockDepartureTransportMeansBuilder = mock[DepartureTransportMeansBuilder]
  private val mockArrivalTransportMeansBuilder = mock[ArrivalTransportMeansBuilder]
  private val mockTransportEquipmentBuilder = mock[TransportEquipmentBuilder]

  private val builder = new ConsignmentBuilder(
    mockGoodsLocationBuilder,
    mockContainerCodeBuilder,
    mockDepartureTransportMeansBuilder,
    mockArrivalTransportMeansBuilder,
    mockTransportEquipmentBuilder
  )

  "ConsignmentBuilder" should {

    "correctly map to the WCO-DEC GoodsShipment.Consignment instance" when {
      "correct data is present" in {
        val borderModeOfTransportCode = "BCode"
        val meansOfTransportOnDepartureType = "T"
        val meansOfTransportOnDepartureIDNumber = "12345"

        val model: ExportsDeclaration =
          aDeclaration(
            withGoodsLocation(GoodsLocationBuilderSpec.correctGoodsLocation),
            withBorderTransport(
              borderModeOfTransportCode,
              meansOfTransportOnDepartureType,
              Some(meansOfTransportOnDepartureIDNumber)
            ),
            withChoice(Choice.StandardDec),
            withWarehouseIdentification(ArrivalTransportMeansBuilderSpec.correctWarehouseIdentification),
            withTransportDetails(Some("Portugal"), container = true, "40", Some("1234567878ui"), Some("A")),
            withContainerData(TransportInformationContainer("container", Seq(Seal("seal1"), Seal("seal2"))))
          )

        val goodsShipment: Declaration.GoodsShipment = new Declaration.GoodsShipment

        builder.buildThenAdd(model, goodsShipment)

        verify(mockGoodsLocationBuilder)
          .buildThenAdd(refEq(GoodsLocationBuilderSpec.correctGoodsLocation), any[GoodsShipment.Consignment])

        verify(mockContainerCodeBuilder)
          .buildThenAdd(
            refEq(
              TransportDetails(
                meansOfTransportCrossingTheBorderNationality = Some("Portugal"),
                container = true,
                meansOfTransportCrossingTheBorderType = "40",
                meansOfTransportCrossingTheBorderIDNumber = Some("1234567878ui"),
                paymentMethod = Some("A")
              )
            ),
            any[GoodsShipment.Consignment]
          )

        verify(mockDepartureTransportMeansBuilder)
          .buildThenAdd(
            refEq(
              BorderTransport(
                borderModeOfTransportCode,
                meansOfTransportOnDepartureType,
                Some(meansOfTransportOnDepartureIDNumber)
              )
            ),
            any[GoodsShipment.Consignment]
          )

        verify(mockArrivalTransportMeansBuilder)
          .buildThenAdd(
            refEq(ArrivalTransportMeansBuilderSpec.correctWarehouseIdentification),
            any[GoodsShipment.Consignment]
          )

        verify(mockTransportEquipmentBuilder)
          .buildThenAdd(
            refEq(
              TransportInformationContainers(
                Seq(TransportInformationContainer("container", Seq(Seal("seal1"), Seal("seal2"))))
              )
            ),
            any[GoodsShipment.Consignment]
          )
      }
    }
  }
}
