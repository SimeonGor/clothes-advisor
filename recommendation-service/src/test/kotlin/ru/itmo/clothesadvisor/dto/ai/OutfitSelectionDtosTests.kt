package ru.itmo.clothesadvisor.dto.ai

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class OutfitSelectionDtosTests {
    private val mapper = JsonMapper.builder().findAndAddModules().build()

    @Test
    fun `request and ordered response round trip with only recommendation fields`() {

        // given
        val request =
            OutfitSelectionRequest(
                OutfitSelectionWeather(
                    BigDecimal("-1.23456789"),
                    BigDecimal("2.30"),
                    "RAIN",
                    "Rain",
                ),
                listOf(
                    OutfitSelectionCandidate(
                        12,
                        "Jacket",
                        "OUTERWEAR",
                        "Outerwear",
                        "Blue",
                        "Wool",
                        "image/png",
                        "AQID",
                    ),
                ),
                occasion = "Work",
                preferredStyle = "Casual",
            )
        val response = OutfitSelectionResponse(listOf(12, 7))

        // when
        val requestJson = mapper.writeValueAsString(request)
        val responseJson = mapper.writeValueAsString(response)
        val decodedRequest = mapper.readValue(requestJson, OutfitSelectionRequest::class.java)
        val decodedResponse = mapper.readValue(responseJson, OutfitSelectionResponse::class.java)

        // then
        assertThat(decodedRequest).isEqualTo(request)
        assertThat(decodedResponse).isEqualTo(response)
        val requestNode = mapper.readTree(requestJson)
        assertThat(requestNode.propertyNames().toList())
            .containsExactlyInAnyOrder("weather", "candidates", "occasion", "preferredStyle")
        assertThat(requestNode.path("occasion").asString()).isEqualTo("Work")
        assertThat(requestNode.path("preferredStyle").asString()).isEqualTo("Casual")
        assertThat(requestNode.path("weather").propertyNames().toList())
            .containsExactlyInAnyOrder(
                "temperatureC",
                "windSpeedMps",
                "precipitationCode",
                "precipitationName",
            )
        assertThat(requestNode.path("weather").path("temperatureC").decimalValue())
            .isEqualByComparingTo(BigDecimal("-1.23456789"))
        assertThat(requestNode.path("weather").path("windSpeedMps").decimalValue())
            .isEqualByComparingTo(BigDecimal("2.30"))
        val candidateNode = requestNode.path("candidates")[0]
        assertThat(candidateNode.propertyNames().toList())
            .containsExactlyInAnyOrder(
                "itemId",
                "name",
                "categoryCode",
                "categoryName",
                "color",
                "material",
                "contentType",
                "imageBase64",
            )
        assertThat(candidateNode.path("imageBase64").asString()).isEqualTo("AQID")
        assertThat(requestJson).doesNotContain("photoId", "ownerId", "s3", "precipitationTypeId")
        assertThat(mapper.readTree(responseJson).path("itemIds").toList().map { it.longValue() })
            .containsExactly(12, 7)
    }
}
