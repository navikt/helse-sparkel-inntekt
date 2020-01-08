package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

class InntektRestClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val stsRestClient: StsRestClient
) {
    suspend fun hentInntektsliste(aktørId: String) =
        httpClient.request<HttpResponse>("$baseUrl/api/v1/hentinntektliste") {
            method = HttpMethod.Post
            header("Authorization", "Bearer ${stsRestClient.token()}")
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            body = mapOf(
                "ident" to mapOf(
                    "identifikator" to aktørId,
                    "aktoerType" to "AKTOER_ID"
                ),
                "ainntektsfilter" to "8-30",
                "formaal" to "Sykepenger",
                "maanedFom" to "2019-01",
                "maanedTom" to "2019-03"
            )
        }
            .let { objectMapper.readValue<ArrayNode>(it.readText()) }
            .map { it.toInntekt() }

}

private fun JsonNode.toInntekt() = Inntekt(
    beløp = this["beloep"].asDouble(),
    inntektstype = Inntektstype.valueOf(this["inntektType"].textValue()),
    orgnummer = this["arbeidsforholdREF"].textValue()
)

data class Inntekt(val beløp: Double, val inntektstype: Inntektstype, val orgnummer: String)

enum class Inntektstype {
    LOENNSINNTEKT,
    NAERINGSINNTEKT,
    PENSJON_ELLER_TRYGD,
    YTELSE_FRA_OFFENTLIGE
}
