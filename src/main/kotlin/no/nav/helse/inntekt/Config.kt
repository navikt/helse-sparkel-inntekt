package no.nav.helse.inntekt

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

const val vaultBase = "/var/run/secrets/nais.io/vault"
val vaultBasePath: Path = Paths.get(vaultBase)

fun readServiceUserCredentials() = ServiceUser(
    username = Files.readString(vaultBasePath.resolve("username")),
    password = Files.readString(vaultBasePath.resolve("password"))
)

fun setUpEnvironment() =
    Environment(
        kafkaBootstrapServers = System.getenv("KAFKA_BOOTSTRAP_SERVERS")
            ?: error("Mangler env var KAFKA_BOOTSTRAP_SERVERS"),
        inntektskomponentBaseUrl = System.getenv("INNTEKTSKOMPONENT_BASE_URL")
            ?: error("Mangler env var INNTEKTSKOMPONENT_BASE_URL")
    )

data class Environment(
    val kafkaBootstrapServers: String,
    val spleisRapidtopic: String = "helse-rapid-v1",
    val stsBaseUrl: String = "http://security-token-service",
    val inntektskomponentBaseUrl: String
)

data class ServiceUser(
    val username: String,
    val password: String
) {
    val basicAuth = "Basic ${Base64.getEncoder().encodeToString("$username:$password".toByteArray())}"
}
