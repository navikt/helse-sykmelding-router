package no.nav.helse

import com.ibm.msg.client.jms.JmsConstants
import com.ibm.msg.client.jms.JmsFactoryFactory
import com.ibm.msg.client.wmq.WMQConstants
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.http.HttpStatusCode
import io.ktor.metrics.micrometer.MicrometerMetrics
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Summary
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource
import java.io.StringReader
import java.lang.RuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jms.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

const val NAMESPACE: String = "sykmelding_router"

val FULL_ROUTE_SUMMARY: Summary = Summary.Builder()
    .name("full_route_summary")
    .namespace(NAMESPACE)
    .help("Time it takes to execute a full route")
    .labelNames("input_queue")
    .register()

val log: Logger = LoggerFactory.getLogger("router")

@Serializable
data class Credentials(
    val mqUsername: String,
    val mqPassword: String
)

@Serializable
data class Config(
    val mqHost: String,
    val mqPort: String,
    val mqChannel: String,
    val mqQueueManager: String,
    val routes: List<QueueRoute>
)

@Serializable
data class QueueRoute(
    val inputQueue: String,
    val outputQueues: List<QueueInfo>,
    @Optional
    val coroutineCount: Int = 4,
    val log: List<QueueLog> = listOf()
)

@Serializable
data class QueueInfo(
    val name: String,
    @Optional
    val failOnException: Boolean = true,
    @Optional
    val behavior: QueueBehavior = QueueBehavior.ALL,
    val matcher: QueueMatcher? = null
)

@Serializable
data class QueueMatcher(
    val extractor: String,
    val pattern: String
)

@Serializable
data class QueueLog(
    val key: String,
    val extractor: String
)

enum class QueueBehavior {
    ALL,
    REMAINDER,
    MATCH
}

data class ProducerMeta(
    val producer: MessageProducer,
    val queueInfo: QueueInfo
)

fun QueueInfo.toProducerMeta(session: Session): ProducerMeta = ProducerMeta(
    session.createProducer(session.createQueue(name)), this
)

data class ApplicationState(var running: Boolean = true, var ready: Boolean = false)

@ImplicitReflectionSerializer
inline fun <reified T : Any> readConfig(path: Path): T = Json.parse(Files.readAllBytes(path).toString(Charsets.UTF_8))
val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, CollectorRegistry.defaultRegistry, Clock.SYSTEM)
val coroutineContext = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
val coroutineScope = CoroutineScope(coroutineContext)

@ImplicitReflectionSerializer
fun main() = runBlocking<Unit>(coroutineContext) {
    val applicationState = ApplicationState()

    val credentials: Credentials = readConfig(Paths.get("/var/run/secrets/nais.io/vault/credentials.json"))
    val configPath = System.getenv("CONFIG_FILE") ?: throw RuntimeException("Missing env variable CONFIG_FILE")
    val config: Config = readConfig(Paths.get(configPath))

    log.info("Connection estabilished towards MQ broker")

    val listenerExceptionHandler = CoroutineExceptionHandler { ctx, e ->
        log.error("Exception caught in coroutine {}", keyValue("context", ctx), e)
    }

    val connectionFactory = createQueueConnection(config)

    val listeners = createListeners(applicationState, connectionFactory, credentials, config.routes, listenerExceptionHandler)
    log.info("Listeners created")

    val ktorServer = createHttpServer(applicationState)

    applicationState.ready = true
    log.info("Application marked as ready to accept traffic")

    while (applicationState.running) {
        if (listeners.flatten().any { !it.isActive || it.isCancelled || it.isCompleted }) {
            log.error("One coroutine seems to have died, shutting down.")
            applicationState.running = false
        }
        delay(100)
    }

    ktorServer.stop(10, 10, TimeUnit.SECONDS)
}

suspend fun createListeners(
    applicationState: ApplicationState,
    connectionFactory: ConnectionFactory,
    credentials: Credentials,
    queueRoutes: List<QueueRoute>,
    exceptionHandler: CoroutineExceptionHandler
) = queueRoutes.map { qmRoute ->
    (1..qmRoute.coroutineCount).map {
        coroutineScope.launch(exceptionHandler) {
            val connection = connectionFactory.createConnection(credentials.mqUsername, credentials.mqPassword)
            connection.start()
            val session = connection.createSession()
            val inputContext = connectionFactory.createContext(credentials.mqUsername, credentials.mqPassword, JMSContext.SESSION_TRANSACTED)
            val input = session.createConsumer(session.createQueue(qmRoute.inputQueue))
            val outputs = qmRoute.outputQueues.map { outputQueue -> outputQueue.toProducerMeta(session) }
            log.info("Route initialized for {} -> {}",
                keyValue("inputQueue", qmRoute.inputQueue),
                keyValue("outputQueues", qmRoute.outputQueues.joinToString(", ")))
            routeMessages(applicationState, inputContext, input, outputs, qmRoute)
        }
    }
}.toList()

data class XPathResult(
    val keyValues: Array<StructuredArgument>,
    val producerMeta: List<ProducerMeta>
)

suspend fun routeMessages(
    applicationState: ApplicationState,
    inputContext: JMSContext,
    input: MessageConsumer,
    output: List<ProducerMeta>,
    route: QueueRoute
) {
    val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

    while (applicationState.running) {
        val inputMessage = input.receiveNoWait()
        if (inputMessage == null) {
            delay(100)
        } else {
            val inputQueueName: String = inputMessage.jmsDestination.name()
            FULL_ROUTE_SUMMARY.labels(inputMessage.jmsDestination.name()).startTimer().use {

                val (extraValuePairs, matches) = try {
                    val xPathFactory = XPathFactory.newInstance()
                    val document = documentBuilder.parse(InputSource(StringReader((inputMessage as TextMessage).text)))
                    val extraValuePairs = route.log
                        .map { keyValue(it.key, xPathFactory.newXPath().evaluate(it.extractor, document)) }
                        .toTypedArray()

                    val producerMeta = output
                        .filter { it.queueInfo.behavior == QueueBehavior.MATCH }
                        .filter {
                            xPathFactory.newXPath().evaluate(it.queueInfo.matcher!!.extractor, document)
                                .matches(Regex(it.queueInfo.matcher.pattern))
                        }
                    XPathResult(extraValuePairs, producerMeta)
                } catch (e: Exception) {
                    log.error("Caught exception while trying to match with xpath and regex", e)
                    XPathResult(route.log.map { keyValue(it.key, "missing") }.toTypedArray(), listOf())
                }
                log.info("Received message from {}, routing to {}",
                    keyValue("inputQueue", inputQueueName),
                    keyValue("outputQueues", output.joinToString(", ") { q -> q.queueInfo.name }))
                val extraValuePairFormat = route.log.joinToString(", ", "(", ")") { "{}" }

                suspend fun List<ProducerMeta>.send(message: Message, logMessage: String) {
                    if (isEmpty())
                        return
                    log.info("$logMessage $extraValuePairFormat",
                        keyValue("inputQueue", inputQueueName),
                        keyValue("outputQueues", joinToString(", ") { q -> q.queueInfo.name }),
                        *extraValuePairs)

                    forEach {
                        it.send(message, inputContext, inputQueueName, extraValuePairFormat, extraValuePairs)
                    }
                }

                if (matches.isEmpty()) {
                    output.filter { it.queueInfo.behavior == QueueBehavior.REMAINDER }
                        .send(inputMessage, "No matches found for input queue {}, routing to {}")
                } else {
                    matches
                        .send(inputMessage, "Message from input queue {} was matched with output queues {}")
                }
                output.filter { it.queueInfo.behavior == QueueBehavior.ALL }
                    .send(inputMessage, "Message was routed from input queue {} to output queues {}")
                inputContext.commit()
            }
        }
    }
}

suspend fun ProducerMeta.send(
    message: Message,
    context: JMSContext,
    inputQueueName: String,
    extraValuePairFormat: String,
    extraValuePairs: Array<StructuredArgument>
) {
    try {
        producer.send(message)
            Counter.builder("message_counter")
            .tags(listOf(
                Tag.of("input_queue", inputQueueName),
                Tag.of("output_queue", queueInfo.name)
            ))
            .register(meterRegistry)
            .increment()
    } catch (e: Throwable) {
        if (queueInfo.failOnException) {
            log.error("Failed to route message from {} to {}, rolling back transaction $extraValuePairFormat",
                keyValue("inputQueue", inputQueueName),
                keyValue("outputQueue", queueInfo.name),
                *extraValuePairs,
                e)
            delay(1000)
            context.rollback()
            throw e
        }
        log.error("Exception caught, failed to route message from {} to {} $extraValuePairFormat",
            keyValue("inputQueue", inputQueueName),
            keyValue("outputQueue", queueInfo.name),
            *extraValuePairs,
            e)
    }
}

fun Destination.name(): String = if (this is Queue) { queueName } else { toString() }

suspend fun createHttpServer(applicationState: ApplicationState) = embeddedServer(CIO, 8080) {
    install(MicrometerMetrics) {
        registry = meterRegistry

        meterBinders = listOf(
            ClassLoaderMetrics(),
            FileDescriptorMetrics(),
            JvmGcMetrics(),
            JvmMemoryMetrics(),
            JvmThreadMetrics(),
            ProcessorMetrics()
        )
    }
    routing {
        get("/is_alive") {
            if (applicationState.running) {
                call.respondText("I'm alive!", status = HttpStatusCode.OK)
            } else {
                call.respondText("I'm dead!", status = HttpStatusCode.InternalServerError)
            }
        }
        get("/is_ready") {
            if (applicationState.ready) {
                call.respondText("I'm ready!", status = HttpStatusCode.OK)
            } else {
                call.respondText("Please wait, I'm not ready!", status = HttpStatusCode.InternalServerError)
            }
        }
        get("/prometheus") {
            call.respondText(meterRegistry.scrape())
        }
    }
}.start(wait = false)

fun createQueueConnection(config: Config): ConnectionFactory = JmsFactoryFactory.getInstance(JmsConstants.WMQ_PROVIDER)
    .createConnectionFactory().apply {
        setBatchProperties(mapOf(
            WMQConstants.WMQ_CONNECTION_MODE to WMQConstants.WMQ_CM_CLIENT,
            WMQConstants.WMQ_QUEUE_MANAGER to config.mqQueueManager,
            WMQConstants.WMQ_HOST_NAME to config.mqHost,
            WMQConstants.WMQ_PORT to config.mqPort,
            WMQConstants.WMQ_CHANNEL to config.mqChannel
        ))
    }
