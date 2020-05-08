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
import java.io.File
import java.lang.RuntimeException
import java.nio.file.Paths
import java.util.concurrent.Executors
import javax.jms.Message
import javax.jms.TextMessage
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory

@ImplicitReflectionSerializer
object SmSpek : Spek({
    val inputMessage = getFileAsStringUTF8("src/test/resources/dialogmelding_dialog_notat.xml")

    val activeMQServer = ActiveMQServers.newActiveMQServer(
        ConfigurationImpl()
            .setPersistenceEnabled(false)
            .setJournalDirectory("target/data/journal")
            .setSecurityEnabled(false)
            .addAcceptorConfiguration("invm", "vm://1"))
    activeMQServer.start()

    val credentials = Credentials("", "")

    val connectionFactory = ActiveMQConnectionFactory("vm://1")
    val queueConnection = connectionFactory.createConnection()
    queueConnection.start()
    val session = queueConnection.createSession()
    val exceptionHandler = CoroutineExceptionHandler { ctx, e ->
        log.error("Exception caught in coroutine {}", StructuredArguments.keyValue("context", ctx), e)
    }

    val config = readConfig<Config>(Paths.get("config-preprod.json"))

    val inputQueue = session.createProducer(session.createQueue(config.routes[0].inputQueue))
    val padmQueue = session.createConsumer(session.createQueue(config.routes[0].outputQueues[0].name))
    val eiaQueue = session.createConsumer(session.createQueue(config.routes[0].outputQueues[1].name))

    afterGroup {
        activeMQServer.stop()
    }

    describe("Configuration for sm2013") {
        val applicationState = ApplicationState()
        val route = GlobalScope.launch(Executors.newSingleThreadExecutor().asCoroutineDispatcher()) {
            createListeners(applicationState, connectionFactory, credentials, config.routes, exceptionHandler).flatten().forEach { it.join() }
        }
        afterGroup {
            runBlocking {
                applicationState.running = false
                route.cancel()
                route.join()
            }
        }

        it("Message with an invalid fnr should end up at the EIA input queue") {
            val sentMessage = inputMessage
            inputQueue.send(session.createTextMessage(sentMessage))

            eiaQueue.receive(10000).text() shouldEqual sentMessage
            padmQueue.receive(100) shouldEqual null
        }


        it("Message with an valid fnr from 1998 should end up at the pale2 input") {
            val sentMessage1998 = getFileAsStringISO88591("src/test/resources/generated_fellesformat_le_fnr_1998.xml")
            inputQueue.send(session.createTextMessage(sentMessage1998))
            padmQueue.receive(10000).text() shouldEqual sentMessage1998
            eiaQueue.receive(100) shouldEqual null
        }

        it("Invalid XML should be routed to EIA input queue") {
            val sentMessage = "HELLOTHISISNOTXML"
            inputQueue.send(session.createTextMessage(sentMessage))
            eiaQueue.receive(10000).text() shouldEqual sentMessage
            padmQueue.receive(100) shouldEqual null
        }
    }
})

fun Message.text(): String? = when (this) {
    is TextMessage -> text
    else -> throw RuntimeException("Unexpected message type ${this::class}")
}

fun main() {
    val xpath = XPathFactory.newInstance().newXPath().compile("/EI_fellesformat/MsgHead/MsgInfo/Patient/Ident[0]/id")
    val document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().parse(File("src/test/resources/dialogmelding_dialog_notat.xml"))
    val value = xpath.evaluate(document)
    println(value)
}
