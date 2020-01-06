package no.nav.helse.sputnik

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.ContentType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class FpsakRestClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    private val stsRestClient: StsRestClient
) {
    suspend fun hentGjeldendeForeldrepengeytelse(aktørId: String): Ytelse? =
        hentYtelse(aktørId, "$baseUrl/fpsak/api/vedtak/gjeldendevedtak-foreldrepenger")
            .map { it.toYtelse() }
            .firstOrNull()

    suspend fun hentGjeldendeSvangerskapsytelse(aktørId: String): Ytelse? =
        hentYtelse(aktørId, "$baseUrl/fpsak/api/vedtak/gjeldendevedtak-svangerskapspenger")
            .map { it.toYtelse() }
            .firstOrNull()

    private suspend fun hentYtelse(aktørId: String, url: String) =
        httpClient.get<HttpResponse>(url) {
            header("Authorization", "Bearer ${stsRestClient.token()}")
            accept(ContentType.Application.Json)
            parameter("aktoerId", aktørId)
        }.let { objectMapper.readValue<ArrayNode>(it.readText()) }

    private fun JsonNode.toYtelse() = Ytelse(
        aktørId = this["aktør"]["verdi"].textValue(),
        fom = this["periode"]["fom"].let { LocalDate.parse(it.textValue()) },
        tom = this["periode"]["tom"].let { LocalDate.parse(it.textValue()) },
        vedtatt = this["vedtattTidspunkt"].let {
            LocalDateTime.parse(
                it.textValue(),
                DateTimeFormatter.ISO_DATE_TIME
            )
        },
        perioder = mapPerioder(this)
    )

    private fun mapPerioder(ytelse: JsonNode): List<Periode> = (ytelse["anvist"] as ArrayNode).map { periode ->
        Periode(
            fom = periode["periode"]["fom"].let { LocalDate.parse(it.textValue()) },
            tom = periode["periode"]["tom"].let { LocalDate.parse(it.textValue()) }
        )
    }
}

data class Ytelse(
    val aktørId: String,
    val fom: LocalDate,
    val tom: LocalDate,
    val vedtatt: LocalDateTime,
    val perioder: List<Periode>
)

data class Periode(
    val fom: LocalDate,
    val tom: LocalDate
)
