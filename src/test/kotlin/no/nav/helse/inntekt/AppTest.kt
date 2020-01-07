package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import no.nav.common.KafkaEnvironment
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.config.SaslConfigs
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AppTest : CoroutineScope {
    override val coroutineContext: CoroutineContext = Executors.newFixedThreadPool(4).asCoroutineDispatcher()

    private val testTopic = "privat-helse-sykepenger-behov"
    private val topicInfos = listOf(
        KafkaEnvironment.TopicInfo(testTopic)
    )

    private val embeddedKafkaEnvironment = KafkaEnvironment(
        autoStart = false,
        noOfBrokers = 1,
        topicInfos = topicInfos,
        withSchemaRegistry = false,
        withSecurity = false
    )

    private val serviceUser = ServiceUser("user", "password")
    private val environment = Environment(
        kafkaBootstrapServers = embeddedKafkaEnvironment.brokersURL,
        spleisBehovtopic = testTopic,
        inntektskomponentBaseUrl = "http://inntektskomponenten.local"
    )

    private val testKafkaProperties = loadBaseConfig(environment, serviceUser).apply {
        this[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "PLAINTEXT"
        this[SaslConfigs.SASL_MECHANISM] = "PLAIN"
    }

    private lateinit var job: Job

    private val behovProducer = KafkaProducer<String, JsonNode>(testKafkaProperties.toProducerConfig())
    private val behovConsumer = KafkaConsumer<String, JsonNode>(testKafkaProperties.toConsumerConfig().also {
        it[ConsumerConfig.GROUP_ID_CONFIG] = "noefornuftigværsåsnill"
    }).also {
        it.subscribe(listOf(testTopic))
    }

    private val mockHttpClient = HttpClient(MockEngine) {
        install(JsonFeature) {
            serializer = JacksonSerializer()
        }
        engine {
            addHandler { request ->
                when {
                    request.url.fullPath.startsWith("/api/v1/hentinntektliste") -> respond("""[]""")
                    else -> respondError(HttpStatusCode.InternalServerError)
                }
            }
        }
    }

    private val tokenExpirationTime get() = LocalDateTime.now().plusDays(1).toEpochSecond(ZoneOffset.UTC)

    private val mockStsRestClient = StsRestClient(
        baseUrl = "",
        serviceUser = ServiceUser("yes", "yes"),
        httpClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond("""{"access_token":"token", "expires_in":$tokenExpirationTime, "token_type":"yes"}""")
                }
            }
        })


    private val inntektsRestClient = InntektRestClient("http://baseUrl.local", mockHttpClient, mockStsRestClient)
    private val løsningService = LøsningService(inntektsRestClient)

    @FlowPreview
    @BeforeAll
    fun setup() {
        embeddedKafkaEnvironment.start()
        job = GlobalScope.launch { launchFlow(environment, serviceUser, løsningService, testKafkaProperties) }
    }

    @Test
    fun `skal motta behov og produsere løsning`() {
        val behov = """{"@id": "behovsid", "@behov":["$Inntektshistorikk", "Sykepengehistorikk"], "aktørId":"123"}"""
        behovProducer.send(ProducerRecord(testTopic, "123", objectMapper.readValue(behov)))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId("behovsid").size)

            val svar = alleSvar.first()
            assertEquals("123", svar["aktørId"].asText())
            assertTrue(svar["@løsning"].hasNonNull("Inntekter"))
        }
    }

    @Test
    fun `skal kun behandle opprinnelig behov`() {
        val behovAlleredeBesvart =
            """{"@id": "1", "@behov":["$Inntektshistorikk", "Sykepengehistorikk"], "aktørId":"123", "@løsning": { "Sykepengehistorikk": [] }}"""
        val behovSomTrengerSvar =
            """{"@id": "2", "@behov":["$Inntektshistorikk", "Sykepengehistorikk"], "aktørId":"123"}"""
        behovProducer.send(ProducerRecord(testTopic, "1", objectMapper.readValue(behovAlleredeBesvart)))
        behovProducer.send(ProducerRecord(testTopic, "2", objectMapper.readValue(behovSomTrengerSvar)))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId("1").size)
            assertEquals(1, alleSvar.medId("2").size)

            val svar = alleSvar.medId("2").first()
            assertEquals("123", svar["aktørId"].asText())

            assertTrue(svar["@løsning"].hasNonNull("Inntekter"))
            assertEquals("2", svar["@id"].asText())
        }
    }

    private fun List<JsonNode>.medId(id: String) = filter { it["@id"].asText() == id }

    private fun assertLøsning(duration: Duration, assertion: (List<JsonNode>) -> Unit) =
        mutableListOf<ConsumerRecord<String, JsonNode>>().apply {
            await()
                .atMost(duration)
                .untilAsserted {
                    addAll(behovConsumer.poll(Duration.ofMillis(100)).toList())
                    assertion(map { it.value() }.filter { it.hasNonNull("@løsning") })
                }
        }

    @AfterAll
    fun tearDown() {
        job.cancel()
        embeddedKafkaEnvironment.close()
    }
}
