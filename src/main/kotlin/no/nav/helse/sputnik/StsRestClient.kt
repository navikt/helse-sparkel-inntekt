package no.nav.helse.sputnik

import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.response.HttpResponse
import io.ktor.client.response.readText
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime

/**
 * henter jwt token fra STS
 */
class StsRestClient(
    private val baseUrl: String,
    private val serviceUser: ServiceUser,
    private val httpClient: HttpClient = HttpClient()
) {
    private var cachedOidcToken: Token = runBlocking { fetchToken() }

    suspend fun token(): String {
        if (cachedOidcToken.expired) cachedOidcToken = fetchToken()
        return cachedOidcToken.access_token
    }

    private suspend fun fetchToken(): Token = httpClient.get<HttpResponse>(
        "$baseUrl/rest/v1/sts/token?grant_type=client_credentials&scope=openid"
    ) {
        header("Authorization", serviceUser.basicAuth)
        accept(ContentType.Application.Json)
    }.let { response ->
        objectMapper.readValue(response.readText())
    }

    internal data class Token(
        internal val access_token: String,
        private val token_type: String,
        private val expires_in: Long
    ) {
        // expire 10 seconds before actual expiry. for great margins.
        private val expirationTime: LocalDateTime = LocalDateTime.now().plusSeconds(expires_in - 10L)
        internal val expired get() = expirationTime.isBefore(LocalDateTime.now())
    }
}
