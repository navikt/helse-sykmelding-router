package no.nav.helse

import com.ibm.msg.client.jms.JmsConstants
import com.ibm.msg.client.jms.JmsFactoryFactory
import com.ibm.msg.client.wmq.WMQConstants
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.prometheus.client.Summary
import kotlinx.coroutines.*
import kotlinx.serialization.Optional
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JSON
import kotlinx.serialization.serializer
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.RuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.jms.*

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
    val outputQueues: List<String>,
    @Optional
    val coroutineCount: Int = 4
)

data class ApplicationState(var running: Boolean = true, var ready: Boolean = false)

inline fun <reified T : Any> readConfig(path: Path): T = JSON.parse(Files.readAllBytes(path).toString(Charsets.UTF_8))

fun main(args: Array<String>) = runBlocking<Unit>(newFixedThreadPoolContext(10, "main-context")) {
    val applicationState = ApplicationState()

    val credentials: Credentials = readConfig(Paths.get("/var/run/secrets/nais.io/vault/credentials.json"))
    val config: Config = readConfig(Paths.get("config.json"))

    val connection = createQueueConnection(config, credentials)

    val listeners = createListeners(applicationState, connection, config.routes)

    val ktorServer = createHttpServer(applicationState)

    applicationState.ready = true

    runBlocking {
        while (applicationState.running) {
            if (listeners.flatten().any { !it.isActive && it.isCancelled && it.isCompleted }) {
                applicationState.running = false
            }
            delay(100)
        }
    }

    ktorServer.stop(10, 10, TimeUnit.SECONDS)
}

suspend fun CoroutineScope.createListeners(
    applicationState: ApplicationState,
    connection: Connection,
    queueRoutes: List<QueueRoute>
) = queueRoutes.map { qmRoute ->
    (1..qmRoute.coroutineCount).map {
        launch {
            val session = connection.createSession()
            val input = session.createConsumer(session.createQueue(qmRoute.inputQueue))
            val outputs = qmRoute.outputQueues.map { outputQueue ->
                session.createProducer(session.createQueue(outputQueue))
            }
            routeMessages(applicationState, input, outputs)
        }
    }
}.toList()

suspend fun routeMessages(applicationState: ApplicationState, input: MessageConsumer, output: List<MessageProducer>) {
    while (applicationState.running) {
        val inputMessage = input.receiveNoWait()
        if (inputMessage == null) {
            delay(100)
        } else {
            FULL_ROUTE_SUMMARY.labels(inputMessage.jmsDestination.name()).startTimer().use {
                log.info("Received message from {}, routing to {}",
                    keyValue("inputQueue", inputMessage.jmsDestination.name()),
                    keyValue("outputQueues", output.joinToString(", ") { q -> q.destination.name() }))
                output.forEach { output -> output.send(inputMessage) }
            }
        }
    }
}

fun Destination.name(): String = if (this is Queue) { queueName } else { toString() }

suspend fun createHttpServer(applicationState: ApplicationState) = embeddedServer(CIO) {
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
    }
}.start(wait = false)

fun createQueueConnection(config: Config, credentials: Credentials): Connection = JmsFactoryFactory.getInstance(JmsConstants.WMQ_PROVIDER)
    .createConnectionFactory().apply {
        setBatchProperties(mapOf(
            WMQConstants.WMQ_CONNECTION_MODE to WMQConstants.WMQ_CM_CLIENT,
            WMQConstants.WMQ_QUEUE_MANAGER to config.mqQueueManager,
            WMQConstants.WMQ_HOST_NAME to config.mqHost,
            WMQConstants.WMQ_PORT to config.mqPort,
            WMQConstants.WMQ_CHANNEL to config.mqChannel
        ))
    }.createConnection(credentials.mqUsername, credentials.mqPassword)
