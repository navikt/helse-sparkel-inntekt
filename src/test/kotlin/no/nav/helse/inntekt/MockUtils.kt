package no.nav.helse.inntekt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.time.ZoneOffset

interface ResponseGenerator {
    fun hentInntekter() = inntekterEmptyResponse()
}

internal fun defaultMockResponseGenerator() = mockk<ResponseGenerator>(relaxed = true) {
    every { hentInntekter() } returns inntekterResponse()
}

internal fun mockHttpClient(mockResponseGenerator: ResponseGenerator) = HttpClient(MockEngine) {
    install(JsonFeature) {
        serializer = JacksonSerializer()
    }
    engine {
        addHandler { request ->
            when {
                request.url.fullPath.startsWith("/api/v1/hentinntektliste") -> respond(mockResponseGenerator.hentInntekter())
                else -> respondError(HttpStatusCode.InternalServerError)
            }
        }
    }
}

fun inntekterEmptyResponse() = """[]"""
fun inntekterResponse() = """
    {
        "arbeidsInntektMaaned": [
            {
                "aarMaaned": "2018-12",
                "arbeidsInntektInformasjon": {
                    "inntektListe": [
                        {
                            "inntektType": "LOENNSINNTEKT",
                            "beloep": 25000,
                            "fordel": "kontantytelse",
                            "inntektskilde": "A-ordningen",
                            "inntektsperiodetype": "Maaned",
                            "inntektsstatus": "LoependeInnrapportert",
                            "leveringstidspunkt": "2020-01",
                            "utbetaltIMaaned": "2018-12",
                            "opplysningspliktig": {
                                "identifikator": "orgnummer1",
                                "aktoerType": "ORGANISASJON"
                            },
                            "virksomhet": {
                                "identifikator": "orgnummer1",
                                "aktoerType": "ORGANISASJON"
                            },
                            "inntektsmottaker": {
                                "identifikator": "aktørId",
                                "aktoerType": "AKTOER_ID"
                            },
                            "inngaarIGrunnlagForTrekk": true,
                            "utloeserArbeidsgiveravgift": true,
                            "informasjonsstatus": "InngaarAlltid",
                            "beskrivelse": "fastloenn"
                        }
                    ]
                }
            },
            {
                "aarMaaned": "2019-05",
                "arbeidsInntektInformasjon": {
                    "inntektListe": [
                        {
                            "inntektType": "LOENNSINNTEKT",
                            "beloep": 25000,
                            "fordel": "kontantytelse",
                            "inntektskilde": "A-ordningen",
                            "inntektsperiodetype": "Maaned",
                            "inntektsstatus": "LoependeInnrapportert",
                            "leveringstidspunkt": "2020-01",
                            "utbetaltIMaaned": "2019-05",
                            "opplysningspliktig": {
                                "identifikator": "orgnummer2",
                                "aktoerType": "ORGANISASJON"
                            },
                            "virksomhet": {
                                "identifikator": "orgnummer2",
                                "aktoerType": "ORGANISASJON"
                            },
                            "inntektsmottaker": {
                                "identifikator": "aktørId",
                                "aktoerType": "AKTOER_ID"
                            },
                            "inngaarIGrunnlagForTrekk": true,
                            "utloeserArbeidsgiveravgift": true,
                            "informasjonsstatus": "InngaarAlltid",
                            "beskrivelse": "fastloenn"
                        }
                    ]
                }
            }
        ],
        "ident": {
            "identifikator": "aktørId",
            "aktoerType": "AKTOER_ID"
        }
    }
"""

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
