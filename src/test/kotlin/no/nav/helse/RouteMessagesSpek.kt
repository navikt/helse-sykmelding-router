package no.nav.helse

import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.any
import org.amshove.kluent.mock
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe
import javax.jms.*

object RouteMessagesSpek : Spek({
    val applicationState = ApplicationState()

    describe("Simple route") {
        val inputContext = mock<JMSContext>()
        val input = mock<Queue>()
        whenever(input.getQueueName()).thenReturn("mock_input")
        val inputQueue = mock<MessageConsumer>()
        val outputQueue1 = mock<MessageProducer>()
        val outputQueue2 = mock<MessageProducer>()
        val producers = listOf(
            ProducerMeta(outputQueue1, "queue1", false),
            ProducerMeta(outputQueue2, "queue2", true)
        )

        val textMessage = mock<TextMessage>()
        whenever(textMessage.getJMSDestination()).thenReturn(input)

        val route = GlobalScope.launch {
            routeMessages(applicationState, inputContext, inputQueue, producers)
        }

        beforeEach {
            reset(inputContext, inputQueue, outputQueue1, outputQueue2)
        }

        afterGroup {
            applicationState.running = false
            runBlocking { route.join() }
        }

        it("Should still route to queue2 whenever queue1 can't receive a message") {
            whenever(inputQueue.receiveNoWait()).thenReturn(textMessage).thenReturn(null)
            whenever(outputQueue1.send(any())).thenThrow(JMSException("Testing"))
            verify(outputQueue2, timeout(5000).times(1)).send(any())
            verify(inputContext, timeout(5000).times(1)).commit()
        }
        it("Rolls back the transaction whenever a queue with failOnException can't receive a message") {
            whenever(inputQueue.receiveNoWait()).thenReturn(textMessage).thenReturn(null)
            whenever(outputQueue2.send(any())).thenThrow(JMSException("Testing"))
            verify(inputContext, timeout(5000).times(1)).rollback()
        }
    }
})