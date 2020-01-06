package no.nav.helse.sputnik

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.fullPath
import java.time.LocalDateTime
import java.time.ZoneOffset

fun fpsakMockClient(mockResponseGenerator: ResponseGenerator) = HttpClient(MockEngine) {
    engine {
        addHandler { request ->
            when {
                request.url.fullPath.startsWith("/fpsak/api/vedtak/gjeldendevedtak-foreldrepenger") -> {
                    respond(mockResponseGenerator.foreldrepenger())
                }
                request.url.fullPath.startsWith("/fpsak/api/vedtak/gjeldendevedtak-svangerskapspenger") -> {
                    respond(mockResponseGenerator.svangerskapspenger())
                }
                else -> error("Endepunktet finnes ikke ${request.url.fullPath}")
            }
        }
    }
}

private val tokenExpirationTime get() = LocalDateTime.now().plusDays(1).toEpochSecond(ZoneOffset.UTC)

internal val mockStsRestClient = StsRestClient(
    baseUrl = "",
    serviceUser = ServiceUser("yes", "yes"),
    httpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond("""{"access_token":"token", "expires_in":$tokenExpirationTime, "token_type":"yes"}""")
            }
        }
    })

interface ResponseGenerator {
    fun foreldrepenger() = ytelseResponse()
    fun svangerskapspenger() = ytelseResponse()
}

fun ytelseResponse() = """
[
    {
        "version": "1.0",
        "aktør": {
            "verdi": "aktør"
        },
        "vedtattTidspunkt": "2019-10-18T00:00:00",
        "type": {
            "kode": "SVP",
            "kodeverk": "FAGSAK_YTELSE_TYPE"
        },
        "saksnummer": "140260023",
        "vedtakReferanse": "20e89c46-9956-4e8d-a0fb-174e079f331f",
        "status": {
            "kode": "LOP",
            "kodeverk": "YTELSE_STATUS"
        },
        "fagsystem": {
            "kode": "FPSAK",
            "kodeverk": "FAGSYSTEM"
        },
        "periode": {
            "fom": "2019-10-01",
            "tom": "2020-02-07"
        },
        "anvist": [
            {
                "periode": {
                    "fom": "2019-10-01",
                    "tom": "2020-02-07"
                },
                "beløp": null,
                "dagsats": null,
                "utbetalingsgrad": null
            }
        ]
    }
]
"""
