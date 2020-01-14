package no.nav.helse.inntekt

import com.fasterxml.jackson.databind.JsonNode
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
import java.time.YearMonth
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

    private val mockResponseGenerator = defaultMockResponseGenerator()
    private val inntektsRestClient =
        InntektRestClient("http://baseUrl.local", mockHttpClient(mockResponseGenerator), mockStsRestClient)
    private val løsningService = LøsningService(inntektsRestClient)

    @FlowPreview
    @BeforeAll
    fun setup() {
        embeddedKafkaEnvironment.start()
        job = GlobalScope.launch { launchFlow(environment, serviceUser, løsningService, testKafkaProperties) }
    }

    @Test
    fun `skal motta behov og produsere løsning`() {
        val start = YearMonth.of(2020, 2)
        val slutt = YearMonth.of(2021, 1)
        behovProducer.send(ProducerRecord(testTopic, "123", behov(start, slutt)))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId("behovsid").size)

            val svar = alleSvar.first()
            assertEquals("123", svar["aktørId"].asText())
            assertTrue(svar["@løsning"].hasNonNull(Inntektsberegning))
            assertEquals(2, svar["@løsning"][Inntektsberegning].size())
        }
    }

    @Test
    fun `skal kun behandle opprinnelig behov`() {
        val start = YearMonth.of(2020, 2)
        val slutt = YearMonth.of(2021, 1)
        val behovAlleredeBesvart = behovMedLøsning(start, slutt, "1")
        val behovSomTrengerSvar = behov(start, slutt, "2")

        behovProducer.send(ProducerRecord(testTopic, "1", behovAlleredeBesvart))
        behovProducer.send(ProducerRecord(testTopic, "2", behovSomTrengerSvar))

        assertLøsning(Duration.ofSeconds(10)) { alleSvar ->
            assertEquals(1, alleSvar.medId("1").size)
            assertEquals(1, alleSvar.medId("2").size)

            val svar = alleSvar.medId("2").first()
            assertEquals("123", svar["aktørId"].asText())

            assertTrue(svar["@løsning"].hasNonNull(Inntektsberegning))
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

    private fun behov(start: YearMonth, slutt: YearMonth, id: String = "behovsid") = objectMapper.valueToTree<JsonNode>(behovMap(start, slutt, id))

    private fun behovMedLøsning(start: YearMonth, slutt: YearMonth, id: String = "behovsid") =
        objectMapper.valueToTree<JsonNode>(behovMap(start, slutt, id) + mapOf("@løsning" to Løsning(emptyList())))

    private fun behovMap(start: YearMonth, slutt: YearMonth, id: String) = mapOf(
        "@id" to id,
        "@behov" to listOf(Inntektsberegning, "EgenAnsatt"),
        "aktørId" to "123",
        "vedtaksperiodeId" to "vedtaksperiodeId",
        "beregningStart" to "$start",
        "beregningSlutt" to "$slutt"
    )
}
