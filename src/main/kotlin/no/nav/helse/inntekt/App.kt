package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
val objectMapper: ObjectMapper = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())
val log: Logger = LoggerFactory.getLogger("sparkel-inntekt")
const val Inntektsberegning = "Inntektsberegning"

@FlowPreview
fun main() = runBlocking {
    val serviceUser = readServiceUserCredentials()
    val environment = setUpEnvironment()

    launchApplication(environment, serviceUser)
}

@FlowPreview
fun launchApplication(
    environment: Environment,
    serviceUser: ServiceUser
) {
    val applicationContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    val exceptionHandler = CoroutineExceptionHandler { context, e ->
        log.error("Feil i lytter", e)
        context.cancel(CancellationException("Feil i lytter", e))
    }
    runBlocking(exceptionHandler + applicationContext) {
        val server = embeddedServer(Netty, 8080) {
            install(MicrometerMetrics) {
                registry = meterRegistry
            }

            routing {
                registerHealthApi({ true }, { true }, meterRegistry)
            }
        }.start(wait = false)

        val stsRestClient = StsRestClient(environment.stsBaseUrl, serviceUser)
        val inntektRestClient = InntektRestClient(
            baseUrl = environment.inntektskomponentBaseUrl,
            httpClient = simpleHttpClient(),
            stsRestClient = stsRestClient
        )
        val løsningService = LøsningService(inntektRestClient)

        launchFlow(environment, serviceUser, løsningService)

        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop(10, 10, TimeUnit.SECONDS)
            applicationContext.close()
        })
    }
}

private fun simpleHttpClient() = HttpClient() {
    install(JsonFeature) {
        this.serializer = JacksonSerializer {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
}

@FlowPreview
suspend fun launchFlow(
    environment: Environment,
    serviceUser: ServiceUser,
    løsningService: LøsningService,
    baseConfig: Properties = loadBaseConfig(environment, serviceUser)
) {
    val behovProducer = KafkaProducer<String, JsonNode>(baseConfig.toProducerConfig())
    KafkaConsumer<String, JsonNode>(baseConfig.toConsumerConfig())
        .apply { subscribe(listOf(environment.spleisRapidtopic)) }
        .asFlow()
        .filterNotNull()
        .filterNot { (_, value) -> value.hasNonNull("@løsning") }
        .filter { (_, value) -> value.hasNonNull("@behov") && value["@behov"].any { it.asText() == Inntektsberegning } }
        .map { (key, value) -> key to løsningService.løsBehov(value) }
        .filter { (_, value) -> value != null }
        .onEach { (key, _) -> log.info("løser behov: {}", keyValue("behovsid", key)) }
        .collect { (key, value) -> behovProducer.send(ProducerRecord(environment.spleisRapidtopic, key, value)) }
}
