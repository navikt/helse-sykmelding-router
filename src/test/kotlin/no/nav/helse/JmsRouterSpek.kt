package no.nav.helse

import kotlinx.coroutines.*
import kotlinx.serialization.ImplicitReflectionSerializer
import net.logstash.logback.argument.StructuredArguments
import org.amshove.kluent.shouldEqual
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl
import org.apache.activemq.artemis.core.server.ActiveMQServers
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.Executors
import javax.jms.*
import kotlin.random.Random

@ImplicitReflectionSerializer
object JmsRouterSpek : Spek({
    val activeMQServer = ActiveMQServers.newActiveMQServer(ConfigurationImpl()
        .setPersistenceEnabled(false)
        .setJournalDirectory("target/data/journal")
        .setSecurityEnabled(false)
        .addAcceptorConfiguration("invm", "vm://0"))
    activeMQServer.start()

    val credentials = Credentials("", "")

    val connectionFactory = ActiveMQConnectionFactory("vm://0")
    val queueConnection = connectionFactory.createConnection()
    queueConnection.start()
    val session = queueConnection.createSession()
    val exceptionHandler = CoroutineExceptionHandler { ctx, e ->
        log.error("Exception caught in coroutine {}", StructuredArguments.keyValue("context", ctx), e)
    }

    afterGroup {
        activeMQServer.stop()
    }

    describe("A route with one input and two outputs") {
        val queueRoute = QueueRoute(
            "input_queue",
            listOf(QueueInfo("output_1"), QueueInfo("output_2"))
        )
        val inputQueue = session.createQueue(queueRoute.inputQueue)
        val outputs = queueRoute.outputQueues.map { it.toProducerMeta(session) }
        val producer = session.createProducer(inputQueue)
        val (consumer1, consumer2) = outputs.map { session.createConsumer(session.createQueue(it.queueInfo.name)) }


        val applicationState = ApplicationState()
        val route = GlobalScope.launch(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
            createListeners(applicationState, connectionFactory, credentials, listOf(queueRoute), exceptionHandler).flatten().forEach { it.join() }
        }

        afterGroup {
            runBlocking {
                applicationState.running = false
                route.cancel()
                route.join()
            }
        }

        it("A single route") {
            val testPayload = "This is a chicken wing"
            producer.send(session.createTextMessage(testPayload))

            val queue1Message = consumer1.receive(10000) as TextMessage
            val queue2Message = consumer2.receive(10000) as TextMessage
            queue1Message.text shouldEqual testPayload
            queue2Message.text shouldEqual testPayload
        }
    }

    describe("Two routes") {
        val queueRoutes = listOf(
            QueueRoute(
                "route_1_input",
                listOf(QueueInfo("route_1_queue_1"), QueueInfo("route_2_queue_2"), QueueInfo("route_3_queue_3"))
            ),
            QueueRoute(
                "route_2_input",
                listOf(QueueInfo("route_2_queue_1"))
            )
        )

        val applicationState = ApplicationState()
        val route = GlobalScope.launch(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
            createListeners(applicationState, connectionFactory, credentials, queueRoutes, exceptionHandler).flatten().forEach { it.join() }
        }

        val (route1Producer, route2Producer) = queueRoutes.map {
            session.createProducer(session.createQueue(it.inputQueue))
        }

        val (route1Consumers, route2Consumers) = queueRoutes.map {
            it.outputQueues.map { queueInfo ->
                session.createConsumer(session.createQueue(queueInfo.name))
            }
        }

        afterGroup {
            runBlocking {
                applicationState.running = false
                route.cancel()
                route.join()
            }
        }

        it("Creates two messages on the route 1 consumers") {
            val testString1 = "This is a test message"
            val testString2 = "Oh wow it can route multiple messages!"

            route1Producer.send(session.createTextMessage(testString1))
            route1Producer.send(session.createTextMessage(testString2))

            val messages = route1Consumers
                .flatMap { consumer -> (1..10).map { consumer.receive(10) } }
                .filterNotNull()
                .map { (it as TextMessage).text }
            messages.size shouldEqual 6
            messages.count { it == testString1 } shouldEqual 3
            messages.count { it == testString2 } shouldEqual 3
        }

        it("Creates one message on route 2 consumer") {

            val testString3 = "Wow it handles multiple routes!!!"

            route2Producer.send(session.createTextMessage(testString3))

            val messages = route2Consumers
                .flatMap { consumer -> (1..10).map { consumer.receive(10) } }
                .filterNotNull()
                .map { (it as TextMessage).text }
            messages.size shouldEqual 1
            messages.count { it == testString3 } shouldEqual 1
        }

        it("Has sufficient performance", timeout = 60000) {
            val messages = (0..20200).map {
                Random.nextBytes(1024).toString(Charsets.ISO_8859_1)
            }

            val messagesWarmup = messages.take(200)
            val messagesRoute1 = messages.take(4000)
            val messagesRoute2 = messages.take(4000)

            messagesWarmup.forEach { route1Producer.send(session.createTextMessage(it)) }
            route1Consumers.forEach { c -> repeat(400) { c.receiveWaitOnNull(5) } }

            println("Starting real run")
            val startTime = System.currentTimeMillis()

            messagesRoute1.forEach { route1Producer.send(session.createTextMessage(it)) }
            messagesRoute2.forEach { route2Producer.send(session.createTextMessage(it)) }

            val receivedRoute1 = route1Consumers.flatMap { q -> (1..6000).mapNotNull { q.receiveWaitOnNull(5) } }
            val receivedRoute2 = route2Consumers.flatMap { q -> (1..6000).mapNotNull { q.receiveWaitOnNull(5) } }

            println("Performance testing took ${System.currentTimeMillis() - startTime} ms")
            val resultsRoute1 = receivedRoute1.map { (it as TextMessage).text }
            val resultsRoute2 = receivedRoute2.map { (it as TextMessage).text }

            messagesRoute1.count { input ->
                resultsRoute1.count { output -> input == output } >= 3
            } shouldEqual 4000
            messagesRoute2.count { input ->
                resultsRoute2.count { output -> input == output } >= 1
            } shouldEqual 4000
        }
    }

    describe("Test can deserialize configuration") {
        it("Valid JSON should parse fine") {
            println(readConfig<Config>(Paths.get("config-prod.json")))
        }
    }
})

fun MessageConsumer.receiveWaitOnNull(timeout: Long): Message? {
    val message = receiveNoWait()
    if (message == null)
        Thread.sleep(timeout)
    return message
}
