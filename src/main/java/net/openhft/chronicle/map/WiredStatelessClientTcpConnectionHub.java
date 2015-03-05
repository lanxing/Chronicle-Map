/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.chronicle.map;

import com.sun.jdi.connect.spi.ClosedConnectionException;
import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.hash.RemoteCallTimeoutException;
import net.openhft.chronicle.hash.impl.util.BuildVersion;
import net.openhft.chronicle.hash.impl.util.CloseablesManager;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.TextWire;
import net.openhft.chronicle.wire.Wire;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static net.openhft.chronicle.map.AbstractChannelReplicator.SIZE_OF_SIZE;
import static net.openhft.chronicle.map.WiredStatelessChronicleMap.EventId.APPLICATION_VERSION;

/**
 * Created by Rob Austin
 */
public class WiredStatelessClientTcpConnectionHub {

    private static final Logger LOG = LoggerFactory.getLogger(StatelessChronicleMap.class);
    private static final byte WIRE_STATELESS_CLIENT_IDENTIFIER = (byte) -126;

    protected final String name;
    protected final InetSocketAddress remoteAddress;
    protected final long timeoutMs;
    protected final int tcpBufferSize;
    private final ReentrantLock inBytesLock = new ReentrantLock(true);
    private final ReentrantLock outBytesLock = new ReentrantLock();
    private final byte[] connectionByte = new byte[1];
    private final ByteBuffer connectionOutBuffer = ByteBuffer.wrap(connectionByte);

    @NotNull
    private final AtomicLong transactionID = new AtomicLong(0);
    @Nullable
    protected CloseablesManager closeables;
    //private net.openhft.chronicle.bytes.Bytes outBytes;
    private final Wire outWire = new TextWire(Bytes.elasticByteBuffer());
    long largestChunkSoFar = 0;
    private final Wire intWire = new TextWire(Bytes.elasticByteBuffer());
    //  used by the enterprise version
    protected int localIdentifier;


    private SocketChannel clientChannel;
    // this is a transaction id and size that has been read by another thread.
    private volatile long parkedTransactionId;

    private volatile long parkedTransactionTimeStamp;
    private long limitOfLast = 0;

    // set up in the header
    private long sizeMark;
    private long startTime;

    public WiredStatelessClientTcpConnectionHub(WiredChronicleMapStatelessClientBuilder config, byte localIdentifier) {
        this.localIdentifier = localIdentifier;
        this.remoteAddress = config.remoteAddress();
        this.tcpBufferSize = config.tcpBufferSize();
        this.name = config.name();

        //  outBytes = Bytes.wrap(outBuffer.slice()).bytes();
        this.timeoutMs = config.timeoutMs();

        attemptConnect(remoteAddress);
        // todo
//        checkVersion();

    }

    private synchronized void attemptConnect(final InetSocketAddress remoteAddress) {

        // ensures that the excising connection are closed
        closeExisting();

        try {
            SocketChannel socketChannel = AbstractChannelReplicator.openSocketChannel(closeables);

            if (socketChannel.connect(remoteAddress)) {
                doHandShaking(socketChannel);
                clientChannel = socketChannel;
            }

        } catch (IOException e) {
            if (closeables != null) closeables.closeQuietly();
            clientChannel = null;
        }
    }

    ReentrantLock inBytesLock() {
        return inBytesLock;
    }

    ReentrantLock outBytesLock() {
        return outBytesLock;
    }

    protected void checkVersion(short channelID) {


        final String serverVersion = serverApplicationVersion(channelID);
        final String clientVersion = clientVersion();

        if (!serverVersion.equals(clientVersion)) {
            LOG.warn("DIFFERENT CHRONICLE-MAP VERSIONS: The Chronicle-Map-Server and " +
                    "Stateless-Client are on different " +
                    "versions, " +
                    " we suggest that you use the same version, server=" + serverApplicationVersion(channelID) + ", " +
                    "client=" + clientVersion);
        }
    }


    private void checkTimeout(long timeoutTime) {
        if (timeoutTime < System.currentTimeMillis())
            throw new RemoteCallTimeoutException();
    }

    protected synchronized void lazyConnect(final long timeoutMs,
                                            final InetSocketAddress remoteAddress) {
        if (clientChannel != null)
            return;

        if (LOG.isDebugEnabled())
            LOG.debug("attempting to connect to " + remoteAddress + " ,name=" + name);

        SocketChannel result;

        long timeoutAt = System.currentTimeMillis() + timeoutMs;

        for (; ; ) {
            checkTimeout(timeoutAt);

            // ensures that the excising connection are closed
            closeExisting();

            try {
                result = AbstractChannelReplicator.openSocketChannel(closeables);
                if (!result.connect(remoteAddress)) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    continue;
                }

                result.socket().setTcpNoDelay(true);
                doHandShaking(result);
                break;
            } catch (IOException e) {
                if (closeables != null) closeables.closeQuietly();
            } catch (Exception e) {
                if (closeables != null) closeables.closeQuietly();
                throw e;
            }
        }
        clientChannel = result;

       /* ByteBuffer byteBuffer = ByteBuffer.allocateDirect(128);
        Bytes<ByteBuffer> bufferBytes = Bytes.wrap(byteBuffer);
        TextWire wire = new TextWire(bufferBytes);
        wire.bytes().skip(4);


        // write out identifier as part of the handshaking
        wire.write(() -> "IDENTIFIER").int8(localIdentifier);

        // write the size
        wire.bytes().writeInt(0, (int) wire.bytes().position());

        // update the write byte buffer
        byteBuffer.limit((int) wire.bytes().position());


        try {
            while (byteBuffer.remaining() > 0) {

                int i = result.write(byteBuffer);
                if (i == -1) {
                    result.close();
                    clientChannel = null;
                    return;
                }

            }
        } catch (Exception e) {
            try {
                result.close();
                clientChannel = null;
            } catch (IOException e1) {
                //
            }

        }*/

    }

    private ByteBuffer outWireByteBuffer() {
        assert outBytesLock().isHeldByCurrentThread();
        return (ByteBuffer) outWire.bytes().underlyingObject();
    }

    /**
     * closes the existing connections and establishes a new closeables
     */
    protected void closeExisting() {
        // ensure that any excising connection are first closed
        if (closeables != null)
            closeables.closeQuietly();

        closeables = new CloseablesManager();
    }

    /**
     * initiates a very simple level of handshaking with the remote server, we send a special ID of
     * -127 ( when the server receives this it knows its dealing with a stateless client, receive
     * back an identifier from the server
     *
     * @param clientChannel clientChannel
     * @throws java.io.IOException
     */
    protected synchronized void doHandShaking(@NotNull final SocketChannel clientChannel) throws IOException {

        connectionByte[0] = TcpReplicator.WIRED_CONNECTION;
        this.connectionOutBuffer.clear();

        long timeoutTime = System.currentTimeMillis() + timeoutMs;

        // write a single byte
        while (connectionOutBuffer.hasRemaining()) {
            clientChannel.write(connectionOutBuffer);
            checkTimeout(timeoutTime);
        }

        this.connectionOutBuffer.clear();

        if (!clientChannel.finishConnect() || !clientChannel.socket().isBound())
            return;

      /*  // read a single byte back
        while (this.connectionOutBuffer.position() <= 4) {
            int read = clientChannel.read(this.connectionOutBuffer);// the remote identifier
            if (read == -1)
                throw new IOException("server connection closed");
            checkTimeout(timeoutTime);
        }

        this.connectionOutBuffer.clear();

        int len = this.connectionOutBuffer.getInt();

        // block till the message is read
        while (this.connectionOutBuffer.position() <= len ) {
            int read = clientChannel.read(this.connectionOutBuffer);// the remote identifier
            if (read == -1)
                throw new IOException("server connection closed");
            checkTimeout(timeoutTime);
        }
        */

      /*  if (LOG.isDebugEnabled())
            LOG.debug("Attached to a map with a remote identifier=" + remoteIdentifier + " ,name=" + name);
*/
    }

    public synchronized void close() {

        if (closeables != null)
            closeables.closeQuietly();

        closeables = null;
        clientChannel = null;

    }

    /**
     * the transaction id are generated as unique timestamps
     *
     * @param time in milliseconds
     * @return a unique transactionId
     */
    long nextUniqueTransaction(long time) {
        long id = time * TcpReplicator.TIMESTAMP_FACTOR;
        for (; ; ) {
            long old = transactionID.get();
            if (old >= id) id = old + 1;
            if (transactionID.compareAndSet(old, id))
                break;
        }
        return id;
    }

    @NotNull
    public String serverApplicationVersion(short channelID) {
        String result = proxyReturnString(APPLICATION_VERSION.toString(), channelID);
        return (result == null) ? "" : result;
    }

    @SuppressWarnings("WeakerAccess")
    @NotNull
    String clientVersion() {
        return BuildVersion.version();
    }


    /**
     * sends data to the server via TCP/IP
     */
    void writeSocket() {

        assert outBytesLock().isHeldByCurrentThread();
        assert !inBytesLock().isHeldByCurrentThread();


        final long timeoutTime = startTime + this.timeoutMs;
        try {

            for (; ; ) {
                if (clientChannel == null) {
                    lazyConnect(timeoutMs, remoteAddress);
                }
                try {

                    writeLength(outWire);

                    // send out all the bytes
                    writeSocket(outWire, timeoutTime);

                    break;

                } catch (@NotNull java.nio.channels.ClosedChannelException | ClosedConnectionException e) {
                    checkTimeout(timeoutTime);
                    lazyConnect(timeoutMs, remoteAddress);
                }
            }
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    private void writeLength(Wire outWire) {
        assert outBytesLock().isHeldByCurrentThread();
        long position = outWire.bytes().position();
        if (position > Integer.MAX_VALUE || position < Integer.MIN_VALUE)
            throw new IllegalStateException("message too large");

        outWire.bytes().writeInt(sizeMark, (int) position);
    }

    protected Wire proxyReply(long timeoutTime, final long transactionId) {

        assert inBytesLock().isHeldByCurrentThread();

        try {

            final Wire wire = proxyReplyThrowable(timeoutTime, transactionId);

            // handle an exception if the message contains the IS_EXCEPTION field
            if (wire.read(() -> "IS_EXCEPTION").bool()) {
                final String text = wire.read(() -> "EXCEPTION").text();
                throw new RuntimeException(text);
            }
            return wire;
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        } catch (RuntimeException e) {
            close();
            throw e;
        } catch (Exception e) {
            close();
            throw new RuntimeException(e);
        } catch (AssertionError e) {
            LOG.error("name=" + name, e);
            throw e;
        }
    }


    private Wire proxyReplyThrowable(long timeoutTime, long transactionId) throws IOException {

        assert inBytesLock().isHeldByCurrentThread();

        for (; ; ) {

            // read the next item from the socket
            if (parkedTransactionId == 0) {

                assert parkedTransactionTimeStamp == 0;

                inWireClear();

                // todo change the size to include the meta data bit
                // reads just the size
                readSocket(SIZE_OF_SIZE, timeoutTime);

                final int messageSize = intWire.bytes().readInt(intWire.bytes().position());


                assert messageSize > 0 : "Invalid message size " + messageSize;
                assert messageSize < 16 << 20 : "Invalid message size " + messageSize;

                final int remainingBytes0 = messageSize - SIZE_OF_SIZE;
                readSocket(remainingBytes0, timeoutTime);

                intWire.bytes().skip(4);
                intWire.bytes().limit(messageSize);

                long  transactionId0 = intWire.read(() -> "TRANSACTION_ID").int64();


                // check the transaction id is reasonable
          /*      assert transactionId0 > 1410000000000L * TcpReplicator.TIMESTAMP_FACTOR :
                        "TransactionId too small " + transactionId0 + " messageSize " + messageSize;
                assert transactionId0 < 2100000000000L * TcpReplicator.TIMESTAMP_FACTOR :
                        "TransactionId too large " + transactionId0 + " messageSize " + messageSize;
*/
                // if the transaction id is for this thread process it
                if (transactionId0 == transactionId) {
                    clearParked();
                    return intWire;

                } else {

                    // if the transaction id is not for this thread, park it
                    // and allow another thread to pick it up
                    parkedTransactionTimeStamp = System.currentTimeMillis();
                    parkedTransactionId = transactionId0;
                    pause();
                    continue;
                }
            }

            // the transaction id was read by another thread, but is for this thread, process it
            if (parkedTransactionId == transactionId) {
                clearParked();
                return intWire;
            }

            // time out the old transaction id
            if (System.currentTimeMillis() - timeoutTime >
                    parkedTransactionTimeStamp) {

                LOG.error("name=" + name, new IllegalStateException("Skipped Message with " +
                        "transaction-id=" +
                        parkedTransactionTimeStamp +
                        ", this can occur when you have another thread which has called the " +
                        "stateless client and terminated abruptly before the message has been " +
                        "returned from the server and hence consumed by the other thread."));

                // read the the next message
                clearParked();
                pause();
            }

        }

    }

    /**
     * clears the wire and its underlying byte buffer
     */
    private void inWireClear() {
        inWireByteBuffer().clear();
        intWire.bytes().clear();
    }

    private void clearParked() {
        assert inBytesLock().isHeldByCurrentThread();
        parkedTransactionId = 0;
        parkedTransactionTimeStamp = 0;
    }

    private void pause() {

        assert !outBytesLock().isHeldByCurrentThread();
        assert inBytesLock().isHeldByCurrentThread();

        /// don't call inBytesLock.isHeldByCurrentThread() as it not atomic
        inBytesLock().unlock();

        // allows another thread to enter hear
        inBytesLock().lock();
    }

    /**
     * reads up to the number of byte in {@code requiredNumberOfBytes} from the socket
     *
     * @param requiredNumberOfBytes the number of bytes to read
     * @param timeoutTime           timeout in milliseconds
     * @return bytes read from the TCP/IP socket
     * @throws java.io.IOException socket failed to read data
     */
    @SuppressWarnings("UnusedReturnValue")
    private Bytes readSocket(int requiredNumberOfBytes, long timeoutTime) throws IOException {

        assert inBytesLock().isHeldByCurrentThread();
        ByteBuffer buffer = inWireByteBuffer();

        while (buffer.position() < requiredNumberOfBytes) {

            int len = clientChannel.read(inWireByteBuffer());

            if (len == -1)
                throw new IORuntimeException("Disconnection to server");

            checkTimeout(timeoutTime);
        }

        return intWire.bytes();
    }

    private ByteBuffer inWireByteBuffer() {
        return (ByteBuffer) intWire.bytes().underlyingObject();
    }

    /**
     * writes the bytes to the socket
     *
     * @param outWire     the data that you wish to write
     * @param timeoutTime how long before a we timeout
     * @throws IOException
     */
    private void writeSocket(Wire outWire, long timeoutTime) throws IOException {

        assert outBytesLock().isHeldByCurrentThread();
        assert !inBytesLock().isHeldByCurrentThread();


        long outBytesPosition = outWire.bytes().position();


        // if we have other threads waiting to send and the buffer is not full, let the other threads
        // write to the buffer
        if (outBytesLock().hasQueuedThreads() &&
                outBytesPosition + largestChunkSoFar <= tcpBufferSize) {
            return;
        }

        ByteBuffer outBuffer = outWireByteBuffer();
        outBuffer.limit((int) outWire.bytes().position());
        outBuffer.position(0);

        upateLargestChunkSoFarSize(outBuffer);

        while (outBuffer.remaining() > 0) {

            int len = clientChannel.write(outBuffer);
            if (len == -1)
                throw new IORuntimeException("Disconnection to server");


            // if we have queued threads then we don't have to write all the bytes as the other
            // threads will write the remains bytes.
            if (outBuffer.remaining() > 0 && outBytesLock().hasQueuedThreads() &&
                    outBuffer.remaining() + largestChunkSoFar <= tcpBufferSize) {

                if (LOG.isDebugEnabled())
                    LOG.debug("continuing -  without all the data being written to the buffer as " +
                            "it will be written by the next thread");
                outBuffer.compact();
                outWire.bytes().limit(outBuffer.limit());
                outWire.bytes().position(outBuffer.position());
                return;
            }

            checkTimeout(timeoutTime);

        }

        outBuffer.clear();
        outWire.bytes().clear();

    }

    /**
     * calculates the size of each chunk
     *
     * @param outBuffer
     */
    private void upateLargestChunkSoFarSize(ByteBuffer outBuffer) {
        int sizeOfThisChunk = (int) (outBuffer.limit() - limitOfLast);
        if (largestChunkSoFar < sizeOfThisChunk)
            largestChunkSoFar = sizeOfThisChunk;

        limitOfLast = outBuffer.limit();
    }


    private long proxySend(@NotNull final String methodName, final long startTime, short channelID) {

        assert outBytesLock().isHeldByCurrentThread();
        assert !inBytesLock().isHeldByCurrentThread();

        // send
        outBytesLock().lock();
        try {
            long transactionId = writeHeader(startTime, channelID);
            outWire.write(() -> "METHOD_NAME").text(methodName);
            writeSocket();
            return transactionId;
        } finally {
            outBytesLock().unlock();
        }
    }

    @SuppressWarnings("SameParameterValue")
    @Nullable
    <O extends Marshallable> O fetchObject(final Class<O> tClass,
                                           @NotNull final String methodName, short channelID) {
        final long startTime = System.currentTimeMillis();
        long transactionId;

        outBytesLock().lock();
        try {
            transactionId = proxySend(methodName, startTime, channelID);
        } finally {
            outBytesLock().unlock();
        }

        long timeoutTime = startTime + this.timeoutMs;

        // receive
        inBytesLock().lock();
        try {
            O result = tClass.newInstance();
            proxyReply(timeoutTime, transactionId).read(() -> "RESULT")
                    .marshallable(result);
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            inBytesLock().unlock();
        }
    }

    @SuppressWarnings("SameParameterValue")
    @Nullable
    String proxyReturnString(@NotNull final String messageId, short channelID) {
        final long startTime = System.currentTimeMillis();
        long transactionId;

        outBytesLock().lock();
        try {
            transactionId = proxySend(messageId, startTime, channelID);
        } finally {
            outBytesLock().unlock();
        }

        long timeoutTime = startTime + this.timeoutMs;

        // receive
        inBytesLock().lock();
        try {
            return proxyReply(timeoutTime, transactionId).read(() -> messageId).text();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            inBytesLock().unlock();
        }
    }


    Wire outWire() {
        assert outBytesLock().isHeldByCurrentThread();
        return outWire;
    }

    long writeHeader(long startTime, short channelID) {

        assert outBytesLock().isHeldByCurrentThread();
        markSize();
        startTime(startTime);

        long transactionId = nextUniqueTransaction(startTime);

        outWire().write(() -> "TRANSACTION_ID").int64(transactionId);
        outWire().write(() -> "TIME_STAMP").int64(startTime);
        outWire().write(() -> "CHANNEL_ID").int16(channelID);

        return transactionId;
    }


    /**
     * mark the location of the outWire size
     */
    void markSize() {

        assert outBytesLock().isHeldByCurrentThread();

        // this is where the size will go
        sizeMark = outWire.bytes().position();


        // skip the 4 bytes for the size
        outWire.bytes().skip(4);
    }

    void startTime(long startTime) {
        this.startTime = startTime;
    }
}
