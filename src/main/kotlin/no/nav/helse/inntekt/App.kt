package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import no.nav.helse.rapids_rivers.RapidApplication
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*

val objectMapper: ObjectMapper = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())

val log: Logger = LoggerFactory.getLogger("no.nav.helse.sparkel-inntekt")
const val Inntektsberegning = "Inntektsberegning"

fun main() {
    val env = System.getenv()

    val serviceUser = ServiceUser(
        username = Files.readString(Paths.get("/var/run/secrets/nais.io/service_user/username")),
        password = Files.readString(Paths.get("/var/run/secrets/nais.io/service_user/password"))
    )

    val stsRestClient = StsRestClient("http://security-token-service.default.svc.nais.local", serviceUser)
    val inntektRestClient = InntektRestClient(
        baseUrl = env.getValue("INNTEKTSKOMPONENT_BASE_URL"),
        httpClient = simpleHttpClient(),
        stsRestClient = stsRestClient
    )

    RapidApplication.create(System.getenv()).apply {
        LøsningService(this, inntektRestClient)
    }.start()
}

private fun simpleHttpClient() = HttpClient() {
    install(JsonFeature) {
        this.serializer = JacksonSerializer {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
}

class ServiceUser(
    val username: String,
    val password: String
) {
    val basicAuth = "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"
}
