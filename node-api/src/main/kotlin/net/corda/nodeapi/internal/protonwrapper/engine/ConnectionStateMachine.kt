package net.corda.nodeapi.internal.protonwrapper.engine

import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.toHexString
import net.corda.nodeapi.internal.ArtemisConstants.MESSAGE_ID_KEY
import net.corda.nodeapi.internal.protonwrapper.messages.MessageStatus
import net.corda.nodeapi.internal.protonwrapper.messages.impl.ReceivedMessageImpl
import net.corda.nodeapi.internal.protonwrapper.messages.impl.SendableMessageImpl
import org.apache.qpid.proton.Proton
import org.apache.qpid.proton.amqp.Binary
import org.apache.qpid.proton.amqp.Symbol
import org.apache.qpid.proton.amqp.messaging.*
import org.apache.qpid.proton.amqp.messaging.Properties
import org.apache.qpid.proton.amqp.messaging.Target
import org.apache.qpid.proton.amqp.transaction.Coordinator
import org.apache.qpid.proton.amqp.transport.ErrorCondition
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode
import org.apache.qpid.proton.amqp.transport.SenderSettleMode
import org.apache.qpid.proton.engine.*
import org.apache.qpid.proton.message.Message
import org.slf4j.MDC
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * This ConnectionStateMachine class handles the events generated by the proton-j library to track
 * various logical connection, transport and link objects and to drive packet processing.
 * It is single threaded per physical SSL connection just like the proton-j library,
 * but this threading lock is managed by the EventProcessor class that calls this.
 * It ultimately posts application packets to/from from the netty transport pipeline.
 */
internal class ConnectionStateMachine(private val serverMode: Boolean,
                                      collector: Collector,
                                      private val localLegalName: String,
                                      private val remoteLegalName: String,
                                      userName: String?,
                                      password: String?) : BaseHandler() {
    companion object {
        private const val CORDA_AMQP_FRAME_SIZE_PROP_NAME = "net.corda.nodeapi.connectionstatemachine.AmqpMaxFrameSize"
        private const val CORDA_AMQP_IDLE_TIMEOUT_PROP_NAME = "net.corda.nodeapi.connectionstatemachine.AmqpIdleTimeout"
        private const val CREATE_ADDRESS_PERMISSION_ERROR = "AMQ119032"

        private val MAX_FRAME_SIZE = Integer.getInteger(CORDA_AMQP_FRAME_SIZE_PROP_NAME, 128 * 1024)
        private val IDLE_TIMEOUT = Integer.getInteger(CORDA_AMQP_IDLE_TIMEOUT_PROP_NAME, 10 * 1000)
        private val log = contextLogger()
    }

    private fun withMDC(block: () -> Unit) {
        val oldMDC = MDC.getCopyOfContextMap() ?: emptyMap<String, String>()
        try {
            MDC.put("serverMode", serverMode.toString())
            MDC.put("localLegalName", localLegalName)
            MDC.put("remoteLegalName", remoteLegalName)
            MDC.put("conn", connection.prettyPrint)
            block()
        } finally {
            MDC.setContextMap(oldMDC)
        }
    }

    private fun logDebugWithMDC(msg: () -> String) {
        if (log.isDebugEnabled) {
            withMDC { log.debug(msg()) }
        }
    }

    private fun logInfoWithMDC(msg: String) = withMDC { log.info(msg) }

    private fun logWarnWithMDC(msg: String, ex: Throwable? = null) = withMDC { log.warn(msg, ex) }

    private fun logErrorWithMDC(msg: String, ex: Throwable? = null) = withMDC { log.error(msg, ex) }

    val connection: Connection
    private val transport: Transport
    private val id = UUID.randomUUID().toString()
    private val sessionState = SessionState()
    /**
     * Key is message topic and value is the list of messages
     */
    private val messageQueues = mutableMapOf<String, LinkedList<SendableMessageImpl>>()
    private val unackedQueue = LinkedList<SendableMessageImpl>()
    private val receivers = mutableMapOf<String, Receiver>()
    private val senders = mutableMapOf<String, Sender>()
    private var tagId: Int = 0

    private val Connection?.prettyPrint: String
        get() = this?.context?.toString() ?: "<n/a>"

    private val Transport?.prettyPrint: String
        // Inside Transport's context - there is Connection, inside Connection's context there is NIO channel that has useful information
        get() = (this?.context as? Endpoint)?.context?.toString() ?: "<n/a>"

    init {
        connection = Engine.connection()
        connection.container = "CORDA:$id"
        transport = Engine.transport()
        transport.idleTimeout = IDLE_TIMEOUT
        transport.context = connection
        @Suppress("UsePropertyAccessSyntax")
        transport.setEmitFlowEventOnSend(true)
        transport.maxFrameSize = MAX_FRAME_SIZE
        connection.collect(collector)
        val sasl = transport.sasl()
        if (userName != null) {
            //TODO This handshake is required for our queue permission logic in Artemis
            sasl.setMechanisms("PLAIN")
            if (serverMode) {
                sasl.server()
                sasl.done(Sasl.PN_SASL_OK)
            } else {
                sasl.plain(userName, password)
                sasl.client()
            }
        } else {
            sasl.setMechanisms("ANONYMOUS")
            if (serverMode) {
                sasl.server()
                sasl.done(Sasl.PN_SASL_OK)
            } else {
                sasl.client()
            }
        }
        transport.bind(connection)
        if (!serverMode) {
            connection.open()
        }
    }

    override fun onConnectionInit(event: Event) {
        val connection = event.connection
        logDebugWithMDC { "Connection init ${connection.prettyPrint}" }
    }

    override fun onConnectionLocalOpen(event: Event) {
        val connection = event.connection
        logInfoWithMDC("Connection local open ${connection.prettyPrint}")
        val session = connection.session()
        session.open()
        sessionState.init(session)
        for (target in messageQueues.keys) {
            getSender(target)
        }
    }

    override fun onConnectionLocalClose(event: Event) {
        val connection = event.connection
        logInfoWithMDC("Connection local close ${connection.prettyPrint}")
        connection.close()
        connection.free()
    }

    override fun onConnectionUnbound(event: Event) {
        val connection = event.connection
        logInfoWithMDC("Connection unbound ${connection.prettyPrint}")
        if (connection == this.connection) {
            val channel = connection.context as? Channel
            if (channel != null) {
                if (channel.isActive) {
                    channel.close()
                }
            }
        }
    }

    override fun onConnectionFinal(event: Event) {
        val connection = event.connection
        logDebugWithMDC { "Connection final ${connection.prettyPrint}" }
        if (connection == this.connection) {
            this.connection.context = null
            for (queue in messageQueues.values) {
                // clear any dead messages
                while (true) {
                    logDebugWithMDC { "Queue size: ${queue.size}" }
                    val msg = queue.poll()
                    if (msg != null) {
                        msg.doComplete(MessageStatus.Rejected)
                        msg.release()
                    } else {
                        break
                    }
                }
            }
            messageQueues.clear()
            while (true) {
                logDebugWithMDC { "Unacked queue size: ${unackedQueue.size}" }
                val msg = unackedQueue.poll()
                if (msg != null) {
                    msg.doComplete(MessageStatus.Rejected)
                    msg.release()
                } else {
                    break
                }
            }
            // shouldn't happen, but close socket channel now if not already done
            val channel = connection.context as? Channel
            if (channel != null && channel.isActive) {
                channel.close()
            }
            // shouldn't happen, but cleanup any stranded items
            transport.context = null
            sessionState.close()
            receivers.clear()
            senders.clear()
        } else {
            logDebugWithMDC { "Connection from the event: ${connection.prettyPrint} is not the connection owned: ${this.connection.prettyPrint}" }
        }
    }

    override fun onTransportHeadClosed(event: Event) {
        val transport = event.transport
        logDebugWithMDC { "Transport Head Closed ${transport.prettyPrint}" }
        transport.close_tail()
        onTransportInternal(transport)
    }

    override fun onTransportTailClosed(event: Event) {
        val transport = event.transport
        logDebugWithMDC { "Transport Tail Closed ${transport.prettyPrint}" }
        transport.close_head()
        onTransportInternal(transport)
    }

    override fun onTransportClosed(event: Event) {
        doTransportClose(event.transport) { "Transport Closed ${transport.prettyPrint}" }
    }

    private fun doTransportClose(transport: Transport?, msg: () -> String) {
        if (transport != null && transport == this.transport && transport.context != null) {
            logDebugWithMDC(msg)
            transport.unbind()
            transport.free()
            transport.context = null
        } else {
            logDebugWithMDC { "Nothing to do in case of: ${msg()}" }
        }
    }

    override fun onTransportError(event: Event) {
        val transport = event.transport
        logInfoWithMDC("Transport Error ${transport.prettyPrint}")
        val condition = event.transport.condition
        if (condition != null) {
            logInfoWithMDC("Error: ${condition.description}")
        } else {
            logInfoWithMDC("Error (no description returned).")
        }
        onTransportInternal(transport)
    }

    override fun onTransport(event: Event) {
        val transport = event.transport
        logDebugWithMDC { "Transport ${transport.prettyPrint}" }
        onTransportInternal(transport)
    }

    private fun onTransportInternal(transport: Transport) {
        if (!transport.isClosed) {
            val pending = transport.pending() // Note this drives frame generation, which the susbsequent writes push to the socket
            if (pending > 0) {
                val connection = transport.context as? Connection
                val channel = connection?.context as? Channel
                channel?.writeAndFlush(transport)
            }
        } else {
            logDebugWithMDC { "Transport is already closed: ${transport.prettyPrint}" }
            doTransportClose(transport) { "Freeing-up resources associated with transport" }
        }
    }

    override fun onSessionInit(event: Event) {
        val session = event.session
        logDebugWithMDC { "Session init $session" }
    }

    override fun onSessionLocalOpen(event: Event) {
        val session = event.session
        logDebugWithMDC { "Session local open $session" }
    }

    private fun getSender(target: String): Sender {
        if (!senders.containsKey(target)) {
            val sender = sessionState.session!!.sender(UUID.randomUUID().toString())
            sender.source = Source().apply {
                address = target
                dynamic = false
                durable = TerminusDurability.NONE
            }
            sender.target = Target().apply {
                address = target
                dynamic = false
                durable = TerminusDurability.UNSETTLED_STATE
            }
            sender.senderSettleMode = SenderSettleMode.UNSETTLED
            sender.receiverSettleMode = ReceiverSettleMode.FIRST
            senders[target] = sender
            sender.open()
        }
        return senders[target]!!
    }

    override fun onSessionLocalClose(event: Event) {
        val session = event.session
        logDebugWithMDC { "Session local close $session" }
        session.close()
        session.free()
    }

    override fun onSessionFinal(event: Event) {
        val session = event.session
        logDebugWithMDC { "Session final for: $session" }
        if (session == sessionState.session) {
            sessionState.close()

            // If TRANSPORT_CLOSED event was already processed, the 'transport' in all subsequent events is set to null.
            // There is, however, a chance of missing TRANSPORT_CLOSED event, e.g. when disconnect occurs before opening remote session.
            // In such cases we must explicitly cleanup the 'transport' in order to guarantee the delivery of CONNECTION_FINAL event.
            doTransportClose(event.transport) { "Missed TRANSPORT_CLOSED in onSessionFinal: force cleanup ${transport.prettyPrint}" }
        }
    }

    override fun onLinkLocalOpen(event: Event) {
        val link = event.link
        if (link is Sender) {
            logDebugWithMDC { "Sender Link local open ${link.name} ${link.source} ${link.target}" }
            senders[link.target.address] = link
            transmitMessages(link)
        }
        if (link is Receiver) {
            logDebugWithMDC { "Receiver Link local open ${link.name} ${link.source} ${link.target}" }
            receivers[link.target.address] = link
        }
    }

    override fun onLinkRemoteOpen(event: Event) {
        val link = event.link
        if (link is Receiver) {
            if (link.remoteTarget is Coordinator) {
                logDebugWithMDC { "Coordinator link received" }
            }
        }
    }

    override fun onLinkRemoteClose(e: Event) {
        val link = e.link
        if (link.remoteCondition != null) {
            val remoteConditionDescription = link.remoteCondition.description
            logWarnWithMDC("Connection closed due to error on remote side: `$remoteConditionDescription`")
            if (remoteConditionDescription.contains(CREATE_ADDRESS_PERMISSION_ERROR)) {
                handleRemoteCreatePermissionError(e)
            }

            transport.condition = link.condition
            transport.close_tail()
            transport.pop(Math.max(0, transport.pending())) // Force generation of TRANSPORT_HEAD_CLOSE (not in C code)
        }
    }

    /**
     * If an the artemis channel does not exist on the counterparty, then a create permission error is returned in the [event].
     * Do not retry messages to this channel as it will result in an infinite loop of retries.
     * Log the error, mark the messages as acknowledged and clear them from the message queue.
     */
    private fun handleRemoteCreatePermissionError(event: Event) {
        val remoteP2PAddress = event.sender.source.address
        logWarnWithMDC("Address does not exist on peer: $remoteP2PAddress. Marking messages sent to this address as Acknowledged.")
        messageQueues[remoteP2PAddress]?.apply {
            forEach { it.doComplete(MessageStatus.Acknowledged) }
            clear()
        }
    }

    override fun onLinkFinal(event: Event) {
        val link = event.link
        if (link is Sender) {
            logDebugWithMDC { "Sender Link final ${link.name} ${link.source} ${link.target}" }
            senders.remove(link.target.address)
        }
        if (link is Receiver) {
            logDebugWithMDC { "Receiver Link final ${link.name} ${link.source} ${link.target}" }
            receivers.remove(link.target.address)
        }
    }

    override fun onLinkFlow(event: Event) {
        val link = event.link
        if (link is Sender) {
            logDebugWithMDC { "Sender Flow event: ${link.name} ${link.source} ${link.target}" }
            if (senders.containsKey(link.target.address)) {
                transmitMessages(link)
            }
        } else if (link is Receiver) {
            logDebugWithMDC { "Receiver Flow event: ${link.name} ${link.source} ${link.target}" }
        }
    }

    fun processTransport() {
        onTransportInternal(transport)
    }

    private fun transmitMessages(sender: Sender) {
        val messageQueue = messageQueues.getOrPut(sender.target.address, { LinkedList() })
        while (sender.credit > 0) {
            logDebugWithMDC { "Sender credit: ${sender.credit}" }
            val nextMessage = messageQueue.poll()
            if (nextMessage != null) {
                try {
                    val messageBuf = nextMessage.buf!!
                    val buf = ByteBuffer.allocate(4)
                    buf.putInt(tagId++)
                    val delivery = sender.delivery(buf.array())
                    delivery.context = nextMessage
                    sender.send(messageBuf.array(), messageBuf.arrayOffset() + messageBuf.readerIndex(), messageBuf.readableBytes())
                    nextMessage.status = MessageStatus.Sent
                    logDebugWithMDC { "Put tag ${delivery.tag.toHexString()} on wire uuid: ${nextMessage.applicationProperties[MESSAGE_ID_KEY]}" }
                    unackedQueue.offer(nextMessage)
                    sender.advance()
                } finally {
                    nextMessage.release()
                }
            } else {
                break
            }
        }
    }

    override fun onDelivery(event: Event) {
        val delivery = event.delivery
        logDebugWithMDC { "Delivery $delivery" }
        val link = delivery.link
        if (link is Receiver) {
            if (delivery.isReadable && !delivery.isPartial) {
                val amqpMessage = decodeAMQPMessage(link)
                val payload = (amqpMessage.body as Data).value.array
                val connection = event.connection
                val channel = connection?.context as? Channel
                if (channel != null) {
                    val appProperties = HashMap(amqpMessage.applicationProperties.value)
                    appProperties["_AMQ_VALIDATED_USER"] = remoteLegalName
                    val localAddress = channel.localAddress() as InetSocketAddress
                    val remoteAddress = channel.remoteAddress() as InetSocketAddress
                    val receivedMessage = ReceivedMessageImpl(
                            payload,
                            link.source.address,
                            remoteLegalName,
                            NetworkHostAndPort(remoteAddress.hostString, remoteAddress.port),
                            localLegalName,
                            NetworkHostAndPort(localAddress.hostString, localAddress.port),
                            appProperties,
                            channel,
                            delivery)
                    logDebugWithMDC { "Full message received uuid: ${appProperties[MESSAGE_ID_KEY]}" }
                    channel.writeAndFlush(receivedMessage)
                    if (link.current() == delivery) {
                        link.advance()
                    }
                } else {
                    delivery.disposition(Rejected())
                    delivery.settle()
                }
            }
        } else if (link is Sender) {
            logDebugWithMDC { "Sender delivery confirmed tag ${delivery.tag.toHexString()}" }
            val ok = delivery.remotelySettled() && delivery.remoteState == Accepted.getInstance()
            val sourceMessage = delivery.context as? SendableMessageImpl
            if (sourceMessage != null) {
                unackedQueue.remove(sourceMessage)
                val status = if (ok) MessageStatus.Acknowledged else MessageStatus.Rejected
                logDebugWithMDC { "Setting status as: $status to message with wire uuid: " +
                        "${sourceMessage.applicationProperties[MESSAGE_ID_KEY]}" }
                sourceMessage.doComplete(status)
            } else {
                logDebugWithMDC { "Source message not found on delivery context" }
            }
            delivery.settle()
        }
    }

    private fun encodeAMQPMessage(message: Message): ByteBuf {
        val buffer = PooledByteBufAllocator.DEFAULT.heapBuffer(1500)
        try {
            try {
                message.encode(NettyWritable(buffer))
                val bytes = ByteArray(buffer.writerIndex())
                buffer.readBytes(bytes)
                return Unpooled.wrappedBuffer(bytes)
            } catch (ex: Exception) {
                logErrorWithMDC("Unable to encode message as AMQP packet", ex)
                throw ex
            }
        } finally {
            buffer.release()
        }
    }

    private fun encodePayloadBytes(msg: SendableMessageImpl): ByteBuf {
        val message = Proton.message()
        message.body = Data(Binary(msg.payload))
        message.isDurable = true
        message.properties = Properties()
        val appProperties = HashMap(msg.applicationProperties)
        //TODO We shouldn't have to do this, but Artemis Server doesn't set the header on AMQP packets.
        // Fortunately, when we are bridge to bridge/bridge to float we can authenticate links there.
        appProperties["_AMQ_VALIDATED_USER"] = localLegalName
        message.applicationProperties = ApplicationProperties(appProperties)
        return encodeAMQPMessage(message)
    }

    private fun decodeAMQPMessage(link: Receiver): Message {
        val amqpMessage = Proton.message()
        val buf = link.recv()
        amqpMessage.decode(buf)
        return amqpMessage
    }

    fun transportWriteMessage(msg: SendableMessageImpl) {
        val encoded = encodePayloadBytes(msg)
        msg.release()
        msg.buf = encoded
        val messageQueue = messageQueues.getOrPut(msg.topic, { LinkedList() })
        messageQueue.offer(msg)
        when (sessionState.value) {
            SessionState.Value.ACTIVE -> {
                val sender = getSender(msg.topic)
                transmitMessages(sender)
            }
            SessionState.Value.UNINITIALIZED -> {
                // This is pretty normal as on Connection Local may not have been received yet
                // Once it will be received the messages will be sent from the `messageQueues`
                logDebugWithMDC { "Session has not been open yet" }
            }
            SessionState.Value.CLOSED -> {
                logInfoWithMDC("Session been closed already")
                // If session been closed then it is too late to send a message, so we flag it as rejected.
                logDebugWithMDC { "Setting Rejected status to message with wire uuid: ${msg.applicationProperties[MESSAGE_ID_KEY]}" }
                msg.doComplete(MessageStatus.Rejected)
            }
        }
    }

    fun transportProcessInput(msg: ByteBuf) {
        val source = msg.nioBuffer()
        try {
            do {
                val buffer = transport.inputBuffer
                val limit = min(buffer.remaining(), source.remaining())
                val duplicate = source.duplicate()
                duplicate.limit(source.position() + limit)
                buffer.put(duplicate)
                transport.processInput().checkIsOk()
                source.position(source.position() + limit)
            } while (source.hasRemaining())
        } catch (ex: Exception) {
            val condition = ErrorCondition()
            condition.condition = Symbol.getSymbol("proton:io")
            condition.description = ex.message
            transport.condition = condition
            transport.close_tail()
            transport.pop(max(0, transport.pending())) // Force generation of TRANSPORT_HEAD_CLOSE (not in C code)
        }
    }

    fun transportProcessOutput(ctx: ChannelHandlerContext) {
        try {
            var done = false
            while (!done) {
                val toWrite = transport.outputBuffer
                if (toWrite != null && toWrite.hasRemaining()) {
                    val outbound = ctx.alloc().buffer(toWrite.remaining())
                    outbound.writeBytes(toWrite)
                    ctx.write(outbound)
                    transport.outputConsumed()
                } else {
                    done = true
                }
            }
            ctx.flush()
        } catch (ex: Exception) {
            val condition = ErrorCondition()
            condition.condition = Symbol.getSymbol("proton:io")
            condition.description = ex.message
            transport.condition = condition
            transport.close_head()
            transport.pop(max(0, transport.pending())) // Force generation of TRANSPORT_HEAD_CLOSE (not in C code)
        }
    }
}