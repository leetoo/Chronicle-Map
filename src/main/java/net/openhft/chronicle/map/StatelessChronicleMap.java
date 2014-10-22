package net.openhft.chronicle.map;

import com.sun.jdi.connect.spi.ClosedConnectionException;
import net.openhft.chronicle.common.StatelessBuilder;
import net.openhft.chronicle.common.exceptions.IORuntimeException;
import net.openhft.chronicle.common.exceptions.TimeoutRuntimeException;
import net.openhft.lang.io.ByteBufferBytes;
import net.openhft.lang.io.Bytes;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static net.openhft.chronicle.map.StatelessChronicleMap.EventId.*;

/**
 * @author Rob Austin.
 */
class StatelessChronicleMap<K, V> implements ChronicleMap<K, V>, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(StatelessChronicleMap.class);

    private final ByteBufferBytes connectionOut = new ByteBufferBytes(ByteBuffer.allocate
            (1024));

    private final ByteBufferBytes connectionIn = new ByteBufferBytes(ByteBuffer.allocate
            (1024));

    private volatile SocketChannel clientChannel;
    public static final byte STATELESS_CLIENT_IDENTIFER = (byte) -127;
    private CloseablesManager closeables;
    private final StatelessBuilder builder;

    static enum EventId {
        HEARTBEAT,
        STATEFUL_UPDATE,
        LONG_SIZE, SIZE,
        IS_EMPTY,
        CONTAINS_KEY,
        CONTAINS_VALUE,
        GET, PUT,
        REMOVE,
        CLEAR,
        KEY_SET,
        VALUES,
        ENTRY_SET,
        REPLACE,
        REPLACE_WITH_OLD_AND_NEW_VALUE,
        PUT_IF_ABSENT,
        REMOVE_WITH_VALUE,
        TO_STRING
    }

    private final AtomicLong transactionID = new AtomicLong();

    StatelessChronicleMap(final KeyValueSerializer<K, V> keyValueSerializer,
                          final StatelessBuilder builder) throws IOException {
        this.keyValueSerializer = keyValueSerializer;
        this.builder = builder;
        attemptConnect(builder.remoteAddress());
    }


    private SocketChannel lazyConnect(final long timeoutMs,
                                      final InetSocketAddress remoteAddress) throws IOException {

        LOG.debug("attempting to connect to " + remoteAddress);

        SocketChannel result = null;

        long timeoutAt = System.currentTimeMillis() + timeoutMs;

        for (; ; ) {

            checkTimeout(timeoutAt);

            // ensures that the excising connection are closed
            closeExisting();

            try {
                result = AbstractChannelReplicator.openSocketChannel(closeables);
                result.connect(builder.remoteAddress());
                doHandShaking(result);
                break;
            } catch (IOException e) {
                closeables.closeQuietly();
            } catch (Exception e) {
                closeables.closeQuietly();
                throw e;
            }

        }

        return result;
    }


    /**
     * attempts a single connect without a timeout and eat any IOException used typically in the
     * constructor, its possible the server is not running with this instance is created, {@link
     * StatelessChronicleMap#lazyConnect(long, java.net.InetSocketAddress)} will attempt to
     * establish the connection when the client make the first map method call.
     *
     * @param remoteAddress
     * @return
     * @throws IOException
     * @see StatelessChronicleMap#lazyConnect(long, java.net.InetSocketAddress)
     */
    private void attemptConnect(final InetSocketAddress remoteAddress) throws IOException {

        // ensures that the excising connection are closed
        closeExisting();

        try {
            clientChannel = AbstractChannelReplicator.openSocketChannel(closeables);
            clientChannel.connect(remoteAddress);
            doHandShaking(clientChannel);

        } catch (IOException e) {
            closeables.closeQuietly();
        }

    }


    private void closeExisting() {

        // ensure that any excising connection are first closed
        if (closeables != null)
            closeables.closeQuietly();

        closeables = new CloseablesManager();
    }


    private void doHandShaking(@NotNull final SocketChannel clientChannel) throws IOException {

        ByteBuffer connectionb = ByteBuffer.allocate(1024);
        final ByteBufferBytes connectionOut = new ByteBufferBytes(connectionb.slice());

        final ByteBufferBytes connectionIn = new ByteBufferBytes(ByteBuffer.allocateDirect(1024));


        connectionOut.clear();
        connectionOut.writeByte(STATELESS_CLIENT_IDENTIFER);

        ByteBuffer buffer = connectionb.slice();
        buffer.position(0);
        buffer.limit(1);
        long timeoutTime = System.currentTimeMillis() + builder.timeoutMs();

        System.out.println("sending handshaing bytes=>" + toString(buffer));
        while (buffer.hasRemaining()) {
            clientChannel.write(buffer);
            checkTimeout((long) timeoutTime);
        }

        connectionIn.buffer().clear();
        connectionIn.clear();
        connectionb.clear();
        connectionOut.clear();

        while (connectionIn.buffer().position() <= 0) {
            clientChannel.read(connectionIn.buffer());
            checkTimeout((long) timeoutTime);
        }

        connectionIn.limit(connectionIn.buffer().position());
        byte remoteIdentifier = connectionIn.readByte();
        connectionIn.clear();

        LOG.info("Attached to a map with a remote identifier=" + remoteIdentifier);

    }

    public File file() {
        return null;
    }

    public void close() {
        if (closeables != null)
            closeables.closeQuietly();
        closeables = null;
        // clearBuffers();
    }


    long nextUniqueTransaction(long time) {

        long l = transactionID.get();
        if (time > l) {
            boolean b = transactionID.compareAndSet(l, time);
            if (b) return time;
        }

        return transactionID.incrementAndGet();
    }

    ByteBuffer b = ByteBuffer.allocateDirect(1024);
    private final ByteBufferBytes out = new ByteBufferBytes(b.slice());

    private final ByteBufferBytes in = new ByteBufferBytes(ByteBuffer.allocateDirect(1024));
    private final KeyValueSerializer<K, V> keyValueSerializer;

    public synchronized V putIfAbsent(K key, V value) {

        long sizeLocation = writeEvent(PUT_IF_ABSENT);
        writeKey(key);
        writeValue(value);
        return readKey(sizeLocation);
    }


    public synchronized boolean remove(Object key, Object value) {
        long sizeLocation = writeEvent(REMOVE_WITH_VALUE);
        writeKey((K) key);
        writeValue((V) value);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();

    }


    public synchronized boolean replace(K key, V oldValue, V newValue) {
        long sizeLocation = writeEvent(REPLACE_WITH_OLD_AND_NEW_VALUE);
        writeKey(key);
        writeValue(oldValue);
        writeValue(newValue);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();
    }


    public synchronized V replace(K key, V value) {
        long sizeLocation = writeEvent(REPLACE);
        writeKey(key);
        writeValue(value);

        // get the data back from the server
        return readKey(sizeLocation);
    }


    public synchronized int size() {
        long sizeLocation = writeEvent(SIZE);

        // get the data back from the server
        return blockingFetch(sizeLocation).readInt();
    }


    public synchronized String toString() {
        long sizeLocation = writeEvent(TO_STRING);

        // get the data back from the server
        return blockingFetch(sizeLocation).readObject(String.class);

    }


    public synchronized boolean isEmpty() {
        long sizeLocation = writeEvent(IS_EMPTY);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();
    }


    public synchronized boolean containsKey(Object key) {
        long sizeLocation = writeEvent(CONTAINS_KEY);
        writeKey((K) key);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();

    }


    public synchronized boolean containsValue(Object value) {
        long sizeLocation = writeEvent(CONTAINS_VALUE);
        writeValue((V) value);

        // get the data back from the server
        return blockingFetch(sizeLocation).readBoolean();
    }


    public synchronized long longSize() {
        long sizeLocation = writeEvent(LONG_SIZE);
        // get the data back from the server
        return blockingFetch(sizeLocation).readLong();
    }


    public synchronized V get(Object key) {
        long sizeLocation = writeEvent(GET);
        writeKey((K) key);

        // get the data back from the server
        return readKey(sizeLocation);

    }


    public synchronized V getUsing(K key, V value) {
        throw new UnsupportedOperationException("getUsing is not supported for stateless clients");
    }


    public synchronized V acquireUsing(K key, V value) {
        throw new UnsupportedOperationException("getUsing is not supported for stateless clients");
    }


    public synchronized V put(K key, V value) {

        long sizeLocation = writeEvent(PUT);
        writeKey(key);
        writeValue(value);

        // get the data back from the server

        return readKey(sizeLocation);

    }

    public synchronized V remove(Object key) {
        long sizeLocation = writeEvent(REMOVE);
        writeKey((K) key);

        // get the data back from the server
        return readKey(sizeLocation);
    }


    public synchronized void putAll(Map<? extends K, ? extends V> map) {

        for (Map.Entry<? extends K, ? extends V> e : map.entrySet()) {
            put(e.getKey(), e.getValue());
        }

    }

    private void writeEntries(Map<? extends K, ? extends V> map) {

        final HashMap<K, V> safeCopy = new HashMap<K, V>(map);

        out.writeStopBit(safeCopy.size());

        final Set<Entry> entries = (Set) safeCopy.entrySet();

        for (Entry e : entries) {
            writeKey(e.getKey());
            writeValue(e.getValue());
        }
    }


    public synchronized void clear() {
        long sizeLocation = writeEvent(CLEAR);

        // get the data back from the server
        blockingFetch(sizeLocation);

    }

    @NotNull
    public synchronized Set<K> keySet() {
        long sizeLocation = writeEvent(KEY_SET);

        // get the data back from the server
        return readKeySet(blockingFetch(sizeLocation));
    }


    @NotNull
    public synchronized Collection<V> values() {
        long sizeLocation = writeEvent(VALUES);

        // get the data back from the server
        final Bytes in = blockingFetch(sizeLocation);

        final long size = in.readStopBit();

        if (size > Integer.MAX_VALUE)
            throw new IllegalStateException("size=" + size + " is too large.");

        final ArrayList<V> result = new ArrayList<V>((int) size);

        for (int i = 0; i < size; i++) {
            result.add(keyValueSerializer.readValue(in));
        }
        return result;
    }


    @NotNull
    public synchronized Set<Map.Entry<K, V>> entrySet() {
        long sizeLocation = writeEvent(ENTRY_SET);

        // get the data back from the server
        Bytes in = blockingFetch(sizeLocation);

        long size = in.readStopBit();

        Set<Map.Entry<K, V>> result = new HashSet<Map.Entry<K, V>>();

        for (int i = 0; i < size; i++) {
            K k = keyValueSerializer.readKey(in);
            V v = keyValueSerializer.readValue(in);
            result.add(new Entry(k, v));
        }

        return result;
    }


    private long writeEvent(EventId event) {
        in.clear();
        out.clear();

        b.clear();

        out.write((byte) event.ordinal());
        long sizeLocation = markSizeLocation();
        return sizeLocation;
    }

    class Entry implements Map.Entry<K, V> {

        final K key;
        final V value;

        /**
         * Creates new entry.
         */
        Entry(K k1, V v) {
            value = v;
            key = k1;
        }

        public final K getKey() {
            return key;
        }

        public final V getValue() {
            return value;
        }

        public final V setValue(V newValue) {
            V oldValue = value;

            StatelessChronicleMap.this.put((K) getKey(), (V) newValue);

            return oldValue;
        }

        public final boolean equals(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry e = (Map.Entry) o;
            Object k1 = getKey();
            Object k2 = e.getKey();
            if (k1 == k2 || (k1 != null && k1.equals(k2))) {
                Object v1 = getValue();
                Object v2 = e.getValue();
                if (v1 == v2 || (v1 != null && v1.equals(v2)))
                    return true;
            }
            return false;
        }

        public final int hashCode() {
            return (key == null ? 0 : key.hashCode()) ^
                    (value == null ? 0 : value.hashCode());
        }

        public final String toString() {
            return getKey() + "=" + getValue();
        }
    }

    private Bytes blockingFetch(long sizeLocation) {

        try {
            return blockingFetchThrowable(sizeLocation, this.builder.timeoutMs());
        } catch (IOException e) {
            close();
            throw new IORuntimeException(e);
        } catch (Exception e) {
            close();
            throw e;
        }

    }

    private void clearBuffers() {
        out.clear();
        b.clear();
        in.clear();
        in.buffer().clear();
    }


    private Bytes blockingFetchThrowable(long sizeLocation, long timeOutMs) throws IOException {


        // free up space in the buffer
        //   compact();

        long startTime = System.currentTimeMillis();
        long timeoutTime = startTime + timeOutMs + 1000000;
        long transactionId = nextUniqueTransaction(startTime);

        for (; ; ) {

            assert out.position() != 0;

            if (clientChannel == null)
                clientChannel = lazyConnect(builder.timeoutMs(), builder.remoteAddress());

            try {

                if (LOG.isDebugEnabled())
                    LOG.debug("sending data with transactionId=" + transactionId);


                write(transactionId);
                System.out.println("out.position()=" + out.position());
                writeSizeAt(sizeLocation);

                // send out all the bytes
                send(out, timeoutTime);

                in.clear();
                in.buffer().clear();

                // the number of bytes in the response
                int size = receive(2, timeoutTime).readUnsignedShort();

                LOG.info("size=" + size);

                receive(size, timeoutTime);

                boolean isException = in.readBoolean();
                long inTransactionId = in.readLong();

                if (inTransactionId != transactionId)
                    throw new IllegalStateException("the received transaction-id=" + inTransactionId +
                            ", does not match the expected transaction-id=" + transactionId);

                if (isException)
                    throw (RuntimeException) in.readObject();

                return in;
            } catch (java.nio.channels.ClosedChannelException e) {
                checkTimeout((long) timeoutTime);
                clientChannel = lazyConnect(builder.timeoutMs(), builder.remoteAddress());
            } catch (ClosedConnectionException e) {
                checkTimeout((long) timeoutTime);
                clientChannel = lazyConnect(builder.timeoutMs(), builder.remoteAddress());
            }
        }
    }

    private void write(long transactionId) {
        out.writeLong(transactionId);
    }

    private ByteBufferBytes receive(int requiredNumberOfBytes, long timeoutTime) throws IOException {

        while (in.buffer().position() < requiredNumberOfBytes) {
            clientChannel.read(in.buffer());
            checkTimeout((long) timeoutTime);
        }

        in.limit(in.buffer().position());
        return in;
    }

    private void send(final ByteBufferBytes out, long timeoutTime) throws IOException {
        b.limit((int) out.position());
        b.position(0);

        System.out.println("sending bytes=>" + toString(b));

        while (b.remaining() > 0) {
            clientChannel.write(b);
            checkTimeout((long) timeoutTime);
        }
        out.clear();
        b.clear();
    }

    public String toString(ByteBuffer b) {
        StringBuilder builder1 = new StringBuilder();
        int i = 0;

        int position = b.position();
        for (int j = b.position(); j < b.limit(); j++) {
            byte b1 = b.get();
            builder1.append((char) b1 + "[" + (int) b1 + "],");
        }
        b.position(position);
        return builder1.toString();
    }

    private void checkTimeout(long timeoutTime) {
        if (timeoutTime < System.currentTimeMillis())
            throw new TimeoutRuntimeException();
    }

    private void writeSizeAt(long locationOfSize) {
        long size = out.position() - locationOfSize;
        out.writeUnsignedShort(locationOfSize, (int) size - 2);
    }

    private long markSizeLocation() {
        long position = out.position();
        out.skip(2);
        return position;
    }

    private void writeKey(K key) {
        keyValueSerializer.writeKey(key, out);
    }

    private V readKey(final long sizeLocation) {
        return keyValueSerializer.readValue(blockingFetch(sizeLocation));
    }

    private void writeValue(V value) {
        keyValueSerializer.writeValue(value, out);
    }

    private Set<K> readKeySet(Bytes in) {
        long size = in.readStopBit();
        final HashSet<K> result = new HashSet<>();

        for (long i = 0; i < size; i++) {
            result.add(keyValueSerializer.readKey(in));
        }
        return result;
    }

}

