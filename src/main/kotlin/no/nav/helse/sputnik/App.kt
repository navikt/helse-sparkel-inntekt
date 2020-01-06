package no.nav.helse.sputnik

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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
val log: Logger = LoggerFactory.getLogger("sputnik")

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
        val fpsakRestClient = FpsakRestClient(
            baseUrl = environment.fpsakBaseUrl,
            httpClient = simpleHttpClient(),
            stsRestClient = stsRestClient
        )

        val løsningService = LøsningService(fpsakRestClient)

        launchFlow(environment, serviceUser, løsningService)

        Runtime.getRuntime().addShutdownHook(Thread {
            server.stop(10, 10, TimeUnit.SECONDS)
            applicationContext.close()
        })
    }
}

private fun simpleHttpClient(serializer: JacksonSerializer? = JacksonSerializer()) = HttpClient() {
    install(JsonFeature) {
        this.serializer = serializer
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
        .apply { subscribe(listOf(environment.spleisBehovtopic)) }
        .asFlow()
        .filterNot { (_, value) -> value.hasNonNull("@løsning") }
        .filter { (_, value) -> value["@behov"].any { it.asText() == "Foreldrepenger" } }
        .map { (key, value) -> key to løsningService.løsBehov(value) }
        .onEach { (key, _) -> log.info("løser behov: {}", keyValue("behovsid", key)) }
        .collect { (key, value) -> behovProducer.send(ProducerRecord(environment.spleisBehovtopic, key, value)) }
}
