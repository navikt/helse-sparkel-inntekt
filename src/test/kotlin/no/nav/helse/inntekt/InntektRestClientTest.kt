package no.nav.helse.inntekt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.http.fullPath
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.YearMonth

class InntektRestClientTest {

    private val responsMock = mockk<Mock>()

    @Test
    fun `person uten inntektshistorikk`() = runBlocking {
        responsMock.apply { every { get() }.returns(tomRespons()) }
        val inntektsliste =
            inntektRestClient.hentInntektsliste("fnr", YearMonth.of(2019, 1), YearMonth.of(2019, 10), "8-30", "callId")

        assertNotNull(inntektsliste)
        assertEquals(0, inntektsliste.size)
    }


    @Test
    fun `person med inntektshistorikk fra org`() = runBlocking {
        responsMock.apply { every { get() }.returns(responsMedInntekt("orgnummer1", "ORGANISASJON")) }
        val inntektsliste =
            inntektRestClient.hentInntektsliste("fnr", YearMonth.of(2019, 1), YearMonth.of(2019, 10), "8-30", "callId")
        assertNotNull(inntektsliste)
        assertEquals(1, inntektsliste.size)
        assertEquals("orgnummer1", inntektsliste.first().inntektsliste.first().orgnummer)
    }

    @Test
    fun `person med inntektshistorikk fra fødselsnummer`() = runBlocking {
        responsMock.apply { every { get() }.returns(responsMedInntekt("fødselsnummer1", "NATURLIG_IDENT")) }
        val inntektsliste =
            inntektRestClient.hentInntektsliste("fnr", YearMonth.of(2019, 1), YearMonth.of(2019, 10), "8-30", "callId")
        assertNotNull(inntektsliste)
        assertEquals(1, inntektsliste.size)
        assertEquals("fødselsnummer1", inntektsliste.first().inntektsliste.first().fødselsnummer)
    }
    @Test
    fun `person med inntektshistorikk fra aktørId`() = runBlocking {
        responsMock.apply { every { get() }.returns(responsMedInntekt("aktørid1", "AKTOER_ID")) }
        val inntektsliste =
            inntektRestClient.hentInntektsliste("fnr", YearMonth.of(2019, 1), YearMonth.of(2019, 10), "8-30", "callId")
        assertNotNull(inntektsliste)
        assertEquals(1, inntektsliste.size)
        assertEquals("aktørid1", inntektsliste.first().inntektsliste.first().aktørId)
    }

    private val inntektRestClient = InntektRestClient(
        "http://localhost.no", HttpClient(MockEngine) {
            install(JsonFeature) {
                this.serializer = JacksonSerializer(jackson = objectMapper)
            }
            engine {
                addHandler { request ->
                    if (request.url.fullPath.startsWith("/api/v1/hentinntektliste")) {
                        respond(responsMock.get())
                    } else {
                        error("Endepunktet finnes ikke ${request.url.fullPath}")
                    }
                }
            }
        },
        mockk { every { runBlocking { token() } }.returns("token") }
    )
}

private fun tomRespons() =
    """{
        "ident": {
            "identifikator": "fnr",
            "aktoerType": "NATURLIG_IDENT"
        }
    }"""

private fun responsMedInntekt(identifikator: String, aktoerType: String) =
    """
        {"arbeidsInntektMaaned": [
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
                                        "identifikator": "$identifikator",
                                        "aktoerType": "$aktoerType"
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
                ]}
"""

private class Mock {
    fun get() = "{}"
}