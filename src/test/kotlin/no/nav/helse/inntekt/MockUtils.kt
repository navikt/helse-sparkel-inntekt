package no.nav.helse.inntekt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import java.time.LocalDateTime
import java.time.ZoneOffset

interface ResponseGenerator {
    fun hentInntekter() = inntekterEmptyResponse()
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
