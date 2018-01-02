package org.komamitsu.fluency;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;
import org.komamitsu.fluency.buffer.PackedForwardBuffer;
import org.komamitsu.fluency.buffer.TestableBuffer;
import org.komamitsu.fluency.flusher.AsyncFlusher;
import org.komamitsu.fluency.flusher.Flusher;
import org.komamitsu.fluency.flusher.SyncFlusher;
import org.komamitsu.fluency.sender.SenderErrorHandler;
import org.komamitsu.fluency.sender.MockTCPSender;
import org.komamitsu.fluency.sender.MultiSender;
import org.komamitsu.fluency.sender.RetryableSender;
import org.komamitsu.fluency.sender.Sender;
import org.komamitsu.fluency.sender.TCPSender;
import org.komamitsu.fluency.sender.failuredetect.FailureDetector;
import org.komamitsu.fluency.sender.failuredetect.PhiAccrualFailureDetectStrategy;
import org.komamitsu.fluency.sender.heartbeat.TCPHeartbeater;
import org.komamitsu.fluency.sender.retry.ExponentialBackOffRetryStrategy;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.msgpack.value.MapValue;
import org.msgpack.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.*;

@RunWith(Theories.class)
public class FluencyTest
{
    private static final Logger LOG = LoggerFactory.getLogger(FluencyTest.class);
    private static final int SMALL_BUF_SIZE = 4 * 1024 * 1024;
    private static final String TMPDIR = System.getProperty("java.io.tmpdir");
    @DataPoints
    public static final Options[] OPTIONS = {
            new Options(false, false, false, false, false), // Normal
            new Options(true, false, false, false, false),  // Failover
            new Options(false, true, false, true, false),   // File backup + Ack response
            new Options(false, false, true, false, false),  // Close instead of flush
            new Options(false, false, false, true, false),  // Ack response
            new Options(false, false, false, false, true),  // Small buffer
            new Options(true, false, false, false, true),   // Failover + Small buffer
            new Options(false, false, true, false, true),   // Close instead of flush + Small buffer
            new Options(false, false, false, true, true),   // Ack response + Small buffer
            new Options(true, false, true, false, false),   // Failover + Close instead of flush
            new Options(false, true, true, true, false),    // File backup + Ack response + Close instead of flush
            new Options(false, false, true, true, false),   // Ack response + Close instead of flush
            new Options(false, false, false, false, false, EmitType.MAP_WITH_EVENT_TIME), // EmitType = MAP_WITH_EVENT_TIME
            new Options(false, false, false, false, false, EmitType.MSGPACK_MAP_VALUE_BYTES), // EmitType = MSGPACK_MAP_VALUE_BYTES
            new Options(false, false, false, false, false, EmitType.MSGPACK_MAP_VALUE_BYTES_WITH_EVENT_TIME), // EmitType = MSGPACK_MAP_VALUE_BYTES_WITH_EVENT_TIME
            new Options(false, false, false, false, false, EmitType.MSGPACK_MAP_VALUE_BYTEBUFFER), // EmitType = MSGPACK_MAP_VALUE_BYTEBUFFER
            new Options(false, false, false, false, false, EmitType.MSGPACK_MAP_VALUE_BYTEBUFFER_WITH_EVENT_TIME), // EmitType = MSGPACK_MAP_VALUE_BYTEBUFFER_WITH_EVENT_TIME
    };

    private enum EmitType
    {
        MAP, MAP_WITH_EVENT_TIME,
        MSGPACK_MAP_VALUE_BYTES, MSGPACK_MAP_VALUE_BYTES_WITH_EVENT_TIME,
        MSGPACK_MAP_VALUE_BYTEBUFFER, MSGPACK_MAP_VALUE_BYTEBUFFER_WITH_EVENT_TIME,
    }

    public static class Options
    {
        private final boolean failover;
        private final boolean fileBackup;
        private final boolean closeInsteadOfFlush;
        private final boolean ackResponse;
        private final boolean smallBuffer;
        private final EmitType emitType;

        public Options(
                boolean failover,
                boolean fileBackup,
                boolean closeInsteadOfFlush,
                boolean ackResponse,
                boolean smallBuffer)
        {
            this(failover, fileBackup, closeInsteadOfFlush, ackResponse, smallBuffer, EmitType.MAP);
        }

        public Options(
                boolean failover,
                boolean fileBackup,
                boolean closeInsteadOfFlush,
                boolean ackResponse,
                boolean smallBuffer,
                EmitType emitType)
        {
            this.failover = failover;
            this.fileBackup = fileBackup;
            this.closeInsteadOfFlush = closeInsteadOfFlush;
            this.ackResponse = ackResponse;
            this.smallBuffer = smallBuffer;
            this.emitType = emitType;
        }

        @Override
        public String toString()
        {
            return "Options{" +
                    "failover=" + failover +
                    ", fileBackup=" + fileBackup +
                    ", closeInsteadOfFlush=" + closeInsteadOfFlush +
                    ", ackResponse=" + ackResponse +
                    ", smallBuffer=" + smallBuffer +
                    ", emitType=" + emitType +
                    '}';
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Options)) {
                return false;
            }

            Options options = (Options) o;

            if (failover != options.failover) {
                return false;
            }
            if (fileBackup != options.fileBackup) {
                return false;
            }
            if (closeInsteadOfFlush != options.closeInsteadOfFlush) {
                return false;
            }
            if (ackResponse != options.ackResponse) {
                return false;
            }
            if (smallBuffer != options.smallBuffer) {
                return false;
            }
            return emitType == options.emitType;
        }

        @Override
        public int hashCode()
        {
            int result = (failover ? 1 : 0);
            result = 31 * result + (fileBackup ? 1 : 0);
            result = 31 * result + (closeInsteadOfFlush ? 1 : 0);
            result = 31 * result + (ackResponse ? 1 : 0);
            result = 31 * result + (smallBuffer ? 1 : 0);
            result = 31 * result + (emitType != null ? emitType.hashCode() : 0);
            return result;
        }
    }

    @Test
    public void testDefaultFluency()
            throws IOException
    {
        {
            Fluency fluency = null;
            try {
                fluency = Fluency.defaultFluency();

                assertThat(fluency.getBuffer(), instanceOf(PackedForwardBuffer.class));
                PackedForwardBuffer buffer = (PackedForwardBuffer) fluency.getBuffer();
                assertThat(buffer.getMaxBufferSize(), is(512 * 1024 * 1024L));
                assertThat(buffer.getFileBackupDir(), is(nullValue()));
                assertThat(buffer.bufferFormatType(), is("packed_forward"));
                assertThat(buffer.getChunkExpandRatio(), is(2f));
                assertThat(buffer.getChunkRetentionSize(), is(4 * 1024 * 1024));
                assertThat(buffer.getChunkInitialSize(), is(1 * 1024 * 1024));
                assertThat(buffer.getChunkRetentionTimeMillis(), is(1000));
                assertThat(buffer.getJvmHeapBufferMode(), is(false));
                assertThat(buffer.isAckResponseMode(), is(false));

                assertThat(fluency.getFlusher(), instanceOf(AsyncFlusher.class));
                AsyncFlusher flusher = (AsyncFlusher) fluency.getFlusher();
                assertThat(flusher.isTerminated(), is(false));
                assertThat(flusher.getFlushIntervalMillis(), is(600));
                assertThat(flusher.getWaitUntilBufferFlushed(), is(60));
                assertThat(flusher.getWaitUntilTerminated(), is(60));

                assertThat(flusher.getSender(), instanceOf(RetryableSender.class));
                RetryableSender retryableSender = (RetryableSender) flusher.getSender();
                assertThat(retryableSender.getRetryStrategy(), instanceOf(ExponentialBackOffRetryStrategy.class));
                ExponentialBackOffRetryStrategy retryStrategy = (ExponentialBackOffRetryStrategy) retryableSender.getRetryStrategy();
                assertThat(retryStrategy.getMaxRetryCount(), is(7));
                assertThat(retryStrategy.getBaseIntervalMillis(), is(400));
                assertThat(retryableSender.getBaseSender(), instanceOf(TCPSender.class));
                TCPSender sender = (TCPSender) retryableSender.getBaseSender();
                assertThat(sender.getHost(), is("127.0.0.1"));
                assertThat(sender.getPort(), is(24224));
                assertThat(sender.getConnectionTimeoutMilli(), is(5000));
                assertThat(sender.getReadTimeoutMilli(), is(5000));

                FailureDetector failureDetector = sender.getFailureDetector();
                assertThat(failureDetector, is(nullValue()));
            }
            finally {
                if (fluency != null) {
                    fluency.close();
                }
            }
        }

        {
            Fluency.defaultFluency(12345).close();
            Fluency.defaultFluency("333.333.333.333", 12345).close();
            Fluency.defaultFluency(Arrays.asList(new InetSocketAddress(43210))).close();
            Fluency.Config config = new Fluency.Config();
            config.setFlushIntervalMillis(200).setMaxBufferSize(Long.MAX_VALUE).setSenderMaxRetryCount(99);
            Fluency.defaultFluency(config).close();
            Fluency.defaultFluency(12345, config).close();
            Fluency.defaultFluency("333.333.333.333", 12345, config).close();
            Fluency.defaultFluency(Arrays.asList(new InetSocketAddress(43210)), config).close();
        }

        {
            Fluency fluency = null;
            try {
                String tmpdir = System.getProperty("java.io.tmpdir");
                assertThat(tmpdir, is(notNullValue()));

                Fluency.Config config =
                        new Fluency.Config()
                                .setFlushIntervalMillis(200)
                                .setMaxBufferSize(Long.MAX_VALUE)
                                .setBufferChunkInitialSize(7 * 1024 * 1024)
                                .setBufferChunkRetentionSize(13 * 1024 * 1024)
                                .setJvmHeapBufferMode(true)
                                .setSenderMaxRetryCount(99)
                                .setAckResponseMode(true)
                                .setWaitUntilBufferFlushed(42)
                                .setWaitUntilFlusherTerminated(24)
                                .setFileBackupDir(tmpdir);

                fluency = Fluency.defaultFluency(
                        Arrays.asList(
                                new InetSocketAddress("333.333.333.333", 11111),
                                new InetSocketAddress("444.444.444.444", 22222)), config);

                assertThat(fluency.getBuffer(), instanceOf(PackedForwardBuffer.class));
                PackedForwardBuffer buffer = (PackedForwardBuffer) fluency.getBuffer();
                assertThat(buffer.getMaxBufferSize(), is(Long.MAX_VALUE));
                assertThat(buffer.getFileBackupDir(), is(tmpdir));
                assertThat(buffer.bufferFormatType(), is("packed_forward"));
                assertThat(buffer.getChunkRetentionTimeMillis(), is(1000));
                assertThat(buffer.getChunkExpandRatio(), is(2f));
                assertThat(buffer.getChunkInitialSize(), is(7 * 1024 * 1024));
                assertThat(buffer.getChunkRetentionSize(), is(13 * 1024 * 1024));
                assertThat(buffer.getJvmHeapBufferMode(), is(true));
                assertThat(buffer.isAckResponseMode(), is(true));

                assertThat(fluency.getFlusher(), instanceOf(AsyncFlusher.class));
                AsyncFlusher flusher = (AsyncFlusher) fluency.getFlusher();
                assertThat(flusher.isTerminated(), is(false));
                assertThat(flusher.getFlushIntervalMillis(), is(200));
                assertThat(flusher.getWaitUntilBufferFlushed(), is(42));
                assertThat(flusher.getWaitUntilTerminated(), is(24));

                assertThat(flusher.getSender(), instanceOf(RetryableSender.class));
                RetryableSender retryableSender = (RetryableSender) flusher.getSender();
                assertThat(retryableSender.getRetryStrategy(), instanceOf(ExponentialBackOffRetryStrategy.class));
                ExponentialBackOffRetryStrategy retryStrategy = (ExponentialBackOffRetryStrategy) retryableSender.getRetryStrategy();
                assertThat(retryStrategy.getMaxRetryCount(), is(99));
                assertThat(retryStrategy.getBaseIntervalMillis(), is(400));

                assertThat(retryableSender.getBaseSender(), instanceOf(MultiSender.class));
                MultiSender multiSender = (MultiSender) retryableSender.getBaseSender();
                assertThat(multiSender.getSenders().size(), is(2));

                assertThat(multiSender.getSenders().get(0), instanceOf(TCPSender.class));
                {
                    TCPSender sender = (TCPSender) multiSender.getSenders().get(0);
                    assertThat(sender.getHost(), is("333.333.333.333"));
                    assertThat(sender.getPort(), is(11111));
                    assertThat(sender.getConnectionTimeoutMilli(), is(5000));
                    assertThat(sender.getReadTimeoutMilli(), is(5000));

                    FailureDetector failureDetector = sender.getFailureDetector();
                    assertThat(failureDetector.getFailureIntervalMillis(), is(3 * 1000));
                    assertThat(failureDetector.getFailureDetectStrategy(), instanceOf(PhiAccrualFailureDetectStrategy.class));
                    assertThat(failureDetector.getHeartbeater(), instanceOf(TCPHeartbeater.class));
                    assertThat(failureDetector.getHeartbeater().getHost(), is("333.333.333.333"));
                    assertThat(failureDetector.getHeartbeater().getPort(), is(11111));
                    assertThat(failureDetector.getHeartbeater().getIntervalMillis(), is(1000));
                }

                {
                    TCPSender sender = (TCPSender) multiSender.getSenders().get(1);
                    assertThat(sender.getHost(), is("444.444.444.444"));
                    assertThat(sender.getPort(), is(22222));
                    assertThat(sender.getConnectionTimeoutMilli(), is(5000));
                    assertThat(sender.getReadTimeoutMilli(), is(5000));

                    FailureDetector failureDetector = sender.getFailureDetector();
                    assertThat(failureDetector.getFailureIntervalMillis(), is(3 * 1000));
                    assertThat(failureDetector.getFailureDetectStrategy(), instanceOf(PhiAccrualFailureDetectStrategy.class));
                    assertThat(failureDetector.getHeartbeater(), instanceOf(TCPHeartbeater.class));
                    assertThat(failureDetector.getHeartbeater().getHost(), is("444.444.444.444"));
                    assertThat(failureDetector.getHeartbeater().getPort(), is(22222));
                    assertThat(failureDetector.getHeartbeater().getIntervalMillis(), is(1000));
                }
            }
            finally {
                if (fluency != null) {
                    fluency.close();
                }
            }
        }
    }

    @Test
    public void testIsTerminated()
            throws IOException, InterruptedException
    {
        Sender sender = new MockTCPSender(24224);
        TestableBuffer.Config bufferConfig = new TestableBuffer.Config();
        {
            Flusher.Instantiator flusherConfig = new AsyncFlusher.Config();
            Fluency fluency = new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
            assertFalse(fluency.isTerminated());
            fluency.close();
            TimeUnit.SECONDS.sleep(1);
            assertTrue(fluency.isTerminated());
        }

        {
            Flusher.Instantiator flusherConfig = new SyncFlusher.Config();
            Fluency fluency = new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
            assertFalse(fluency.isTerminated());
            fluency.close();
            TimeUnit.SECONDS.sleep(1);
            assertTrue(fluency.isTerminated());
        }
    }

    @Test
    public void testGetAllocatedBufferSize()
            throws IOException
    {
        Fluency fluency = new Fluency.Builder(new MockTCPSender(24224)).
                setBufferConfig(new TestableBuffer.Config()).
                setFlusherConfig(new AsyncFlusher.Config()).
                build();
        assertThat(fluency.getAllocatedBufferSize(), is(0L));
        Map<String, Object> map = new HashMap<String, Object>();
        map.put("comment", "hello world");
        for (int i = 0; i < 10000; i++) {
            fluency.emit("foodb.bartbl", map);
        }
        assertThat(fluency.getAllocatedBufferSize(), is(TestableBuffer.ALLOC_SIZE * 10000L));
    }

    @Test
    public void testWaitUntilFlusherTerminated()
            throws IOException, InterruptedException
    {
        {
            Sender sender = new MockTCPSender(24224);
            TestableBuffer.Config bufferConfig = new TestableBuffer.Config().setWaitBeforeCloseMillis(2000);
            AsyncFlusher.Config flusherConfig = new AsyncFlusher.Config().setWaitUntilTerminated(0);
            Fluency fluency = new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
            fluency.emit("foo.bar", new HashMap<String, Object>());
            fluency.close();
            assertThat(fluency.waitUntilFlusherTerminated(1), is(false));
        }

        {
            Sender sender = new MockTCPSender(24224);
            TestableBuffer.Config bufferConfig = new TestableBuffer.Config().setWaitBeforeCloseMillis(2000);
            AsyncFlusher.Config flusherConfig = new AsyncFlusher.Config().setWaitUntilTerminated(0);
            Fluency fluency = new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
            fluency.emit("foo.bar", new HashMap<String, Object>());
            fluency.close();
            assertThat(fluency.waitUntilFlusherTerminated(3), is(true));
        }
    }

    @Test
    public void testWaitUntilFlushingAllBuffer()
            throws IOException, InterruptedException
    {
        {
            Sender sender = new MockTCPSender(24224);
            TestableBuffer.Config bufferConfig = new TestableBuffer.Config();
            Flusher.Instantiator flusherConfig = new AsyncFlusher.Config().setFlushIntervalMillis(2000);
            Fluency fluency = null;
            try {
                fluency = new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
                fluency.emit("foo.bar", new HashMap<String, Object>());
                assertThat(fluency.waitUntilAllBufferFlushed(3), is(true));
            }
            finally {
                if (fluency != null) {
                    fluency.close();
                }
            }
        }

        {
            Sender sender = new MockTCPSender(24224);
            TestableBuffer.Config bufferConfig = new TestableBuffer.Config();
            Flusher.Instantiator flusherConfig = new AsyncFlusher.Config().setFlushIntervalMillis(2000);
            Fluency fluency = null;
            try {
                fluency = new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
                fluency.emit("foo.bar", new HashMap<String, Object>());
                assertThat(fluency.waitUntilAllBufferFlushed(1), is(false));
            }
            finally {
                if (fluency != null) {
                    fluency.close();
                }
            }
        }
    }

    @Test
    public void testSenderErrorHandler()
            throws IOException, InterruptedException
    {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final AtomicReference<Throwable> errorContainer = new AtomicReference<Throwable>();

        Fluency fluency = Fluency.defaultFluency(Integer.MAX_VALUE,
                new Fluency.Config()
                        .setSenderMaxRetryCount(1)
                        .setSenderErrorHandler(new SenderErrorHandler()
                        {
                            @Override
                            public void handle(Throwable e)
                            {
                                errorContainer.set(e);
                                countDownLatch.countDown();
                            }
                        }));

        HashMap<String, Object> event = new HashMap<String, Object>();
        event.put("name", "foo");
        fluency.emit("tag", event);

        if (!countDownLatch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Timeout");
        }

        assertThat(errorContainer.get(), is(instanceOf(RetryableSender.RetryOverException.class)));
    }

    private interface WithServerPort
    {
        void run(int serverPort)
                throws Exception;
    }

    private Exception withReadingEverythingTCPServer(final WithServerPort testTask, long timeoutMilli)
            throws Throwable
    {
        ExecutorService executorService = Executors.newCachedThreadPool();
        final ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        try {
            serverSocketChannel.socket().bind(new InetSocketAddress(0));
            final int serverPort = serverSocketChannel.socket().getLocalPort();

            executorService.submit(new Callable<Void>()
            {
                @Override
                public Void call()
                        throws Exception
                {
                    SocketChannel socketChannel = serverSocketChannel.accept();
                    try {
                        ByteBuffer buffer = ByteBuffer.allocate(256);
                        // Just read everything and ignore it
                        while (socketChannel.read(buffer) > 0) {
                        }
                    }
                    finally {
                        socketChannel.close();
                    }
                    return null;
                }
            });

            Future<Void> testTaskFuture = executorService.submit(new Callable<Void>()
            {
                @Override
                public Void call()
                        throws Exception
                {
                    testTask.run(serverPort);
                    return null;
                }
            });

            try {
                testTaskFuture.get(timeoutMilli, TimeUnit.MILLISECONDS);
            }
            catch (Exception e) {
                return e;
            }
        }
        finally {
            executorService.shutdownNow();
            serverSocketChannel.close();
        }
        return null;
    }

    @Test
    public void testAckResponse()
            throws Throwable
    {
        {
            Exception exception = withReadingEverythingTCPServer(new WithServerPort()
            {
                @Override
                public void run(int serverPort)
                        throws Exception
                {
                    Fluency fluency = Fluency.defaultFluency(serverPort);
                    fluency.emit("foo.bar", new HashMap<String, Object>());
                    fluency.close();
                }
            }, 5000);
            assertNull(exception);
        }

        {
            Exception exception = withReadingEverythingTCPServer(new WithServerPort()
            {
                @Override
                public void run(int serverPort)
                        throws Exception
                {
                    Fluency fluency = Fluency.defaultFluency(new Fluency.Config().setAckResponseMode(true));
                    fluency.emit("foo.bar", new HashMap<String, Object>());
                    fluency.close();
                }
            }, 5000);
            assertEquals(exception.getClass(), TimeoutException.class);
        }
    }

    interface FluencyFactory
    {
        Fluency generate(List<Integer> localPort)
                throws IOException;
    }

    private Sender getSingleTCPSender(int port)
    {
        return new TCPSender.Config().setPort(port).createInstance();
    }

    private Sender getDoubleTCPSender(int firstPort, int secondPort)
    {
        return new MultiSender.Config(
                Arrays.<Sender.Instantiator>asList(
                    new TCPSender.Config()
                            .setPort(firstPort)
                            .setHeartbeaterConfig(new TCPHeartbeater.Config().setPort(firstPort)),
                    new TCPSender.Config()
                            .setPort(secondPort)
                            .setHeartbeaterConfig(new TCPHeartbeater.Config().setPort(secondPort))
                )).createInstance();
    }

    @Theory
    public void testFluencyUsingAsyncFlusher(final Options options)
            throws Exception
    {
        testFluencyBase(new FluencyFactory()
        {
            @Override
            public Fluency generate(List<Integer> localPorts)
                    throws IOException
            {
                Sender sender;
                int fluentdPort = localPorts.get(0);
                if (options.failover) {
                    int secondaryFluentdPort = localPorts.get(1);
                    sender = getDoubleTCPSender(fluentdPort, secondaryFluentdPort);
                }
                else {
                    sender = getSingleTCPSender(fluentdPort);
                }
                PackedForwardBuffer.Config bufferConfig = new PackedForwardBuffer.Config();
                if (options.ackResponse) {
                    bufferConfig.setAckResponseMode(true);
                }
                if (options.smallBuffer) {
                    bufferConfig.setMaxBufferSize(SMALL_BUF_SIZE);
                }
                if (options.fileBackup) {
                    bufferConfig.setFileBackupDir(TMPDIR).setFileBackupPrefix("testFluencyUsingAsyncFlusher" + options.hashCode());
                }
                Flusher.Instantiator flusherConfig = new AsyncFlusher.Config()
                        .setWaitUntilBufferFlushed(10)
                        .setWaitUntilTerminated(10);
                return new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
            }
        }, options);
    }

    @Theory
    public void testFluencyUsingSyncFlusher(final Options options)
            throws Exception
    {
        testFluencyBase(new FluencyFactory()
        {
            @Override
            public Fluency generate(List<Integer> localPorts)
                    throws IOException
            {
                Sender sender;
                int fluentdPort = localPorts.get(0);
                if (options.failover) {
                    int secondaryFluentdPort = localPorts.get(1);
                    sender = getDoubleTCPSender(fluentdPort, secondaryFluentdPort);
                }
                else {
                    sender = getSingleTCPSender(fluentdPort);
                }
                PackedForwardBuffer.Config bufferConfig = new PackedForwardBuffer.Config();
                if (options.ackResponse) {
                    bufferConfig.setAckResponseMode(true);
                }
                if (options.smallBuffer) {
                    bufferConfig.setMaxBufferSize(SMALL_BUF_SIZE);
                }
                if (options.fileBackup) {
                    bufferConfig.setFileBackupDir(TMPDIR).setFileBackupPrefix("testFluencyUsingSyncFlusher" + options.hashCode());
                }
                Flusher.Instantiator flusherConfig = new SyncFlusher.Config()
                        .setWaitUntilBufferFlushed(10)
                        .setWaitUntilTerminated(10);
                return new Fluency.Builder(sender).setBufferConfig(bufferConfig).setFlusherConfig(flusherConfig).build();
            }
        }, options);
    }

    private void testFluencyBase(final FluencyFactory fluencyFactory, final Options options)
            throws Exception
    {
        LOG.info("testFluencyBase starts: options={}", options);

        final ArrayList<Integer> localPorts = new ArrayList<Integer>();

        final MockFluentdServer fluentd = new MockFluentdServer();
        fluentd.start();

        final MockFluentdServer secondaryFluentd = new MockFluentdServer(fluentd);
        secondaryFluentd.start();

        TimeUnit.MILLISECONDS.sleep(200);

        localPorts.add(fluentd.getLocalPort());
        localPorts.add(secondaryFluentd.getLocalPort());

        final AtomicReference<Fluency> fluency = new AtomicReference<Fluency>(fluencyFactory.generate(localPorts));
        if (options.fileBackup) {
            fluency.get().clearBackupFiles();
        }

        final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

        final int maxNameLen = 200;
        final HashMap<Integer, String> nameLenTable = new HashMap<Integer, String>(maxNameLen);
        for (int i = 1; i <= maxNameLen; i++) {
            StringBuilder stringBuilder = new StringBuilder();
            for (int j = 0; j < i; j++) {
                stringBuilder.append('x');
            }
            nameLenTable.put(i, stringBuilder.toString());
        }

        final AtomicLong ageEventsSum = new AtomicLong();
        final AtomicLong nameEventsLength = new AtomicLong();
        final AtomicLong tag0EventsCounter = new AtomicLong();
        final AtomicLong tag1EventsCounter = new AtomicLong();
        final AtomicLong tag2EventsCounter = new AtomicLong();
        final AtomicLong tag3EventsCounter = new AtomicLong();

        try {
            final Random random = new Random();
            int concurrency = 10;
            final int reqNum = 6000;
            long start = System.currentTimeMillis();
            final CountDownLatch latch = new CountDownLatch(concurrency);
            final AtomicBoolean shouldFailOver = new AtomicBoolean(true);

            final AtomicBoolean shouldStopFluentd = new AtomicBoolean(true);
            final AtomicBoolean shouldStopFluency = new AtomicBoolean(true);
            final CountDownLatch fluentdCloseWaitLatch = new CountDownLatch(concurrency);
            final CountDownLatch fluencyCloseWaitLatch = new CountDownLatch(concurrency);

            ExecutorService es = Executors.newCachedThreadPool();
            for (int i = 0; i < concurrency; i++) {
                es.execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        for (int i = 0; i < reqNum; i++) {
                            if (Thread.currentThread().isInterrupted()) {
                                LOG.info("Interrupted...");
                                break;
                            }

                            if (options.failover) {
                                if (i == reqNum / 2) {
                                    if (shouldFailOver.getAndSet(false)) {
                                        LOG.info("Failing over...");
                                        try {
                                            secondaryFluentd.stop();
                                        }
                                        catch (IOException e) {
                                            LOG.warn("Failed to stop secondary fluentd", e);
                                        }
                                    }
                                }
                            }
                            else if (options.fileBackup) {
                                if (i == reqNum / 2) {
                                    if (shouldStopFluentd.getAndSet(false)) {
                                        LOG.info("Stopping Fluentd...");
                                        try {
                                            fluentd.stop();
                                            secondaryFluentd.stop();
                                        }
                                        catch (IOException e) {
                                            LOG.warn("Failed to stop Fluentd", e);
                                        }
                                    }

                                    fluentdCloseWaitLatch.countDown();
                                    try {
                                        assertTrue(fluentdCloseWaitLatch.await(20, TimeUnit.SECONDS));
                                    }
                                    catch (InterruptedException e) {
                                        LOG.warn("Interrupted", e);
                                    }

                                    if (shouldStopFluency.getAndSet(false)) {
                                        LOG.info("Stopping Fluency...");
                                        try {
                                            fluency.get().close();
                                            TimeUnit.SECONDS.sleep(2);
                                        }
                                        catch (Exception e) {
                                            LOG.warn("Failed to stop Fluency", e);
                                        }

                                        LOG.info("Restarting Fluentd...");
                                        try {
                                            fluentd.start();
                                            secondaryFluentd.start();
                                            TimeUnit.MILLISECONDS.sleep(200);
                                            LOG.info("Restarting Fluency...");
                                            fluency.set(fluencyFactory.generate(Arrays.asList(fluentd.getLocalPort(), secondaryFluentd.getLocalPort())));
                                            TimeUnit.SECONDS.sleep(2);
                                        }
                                        catch (Exception e) {
                                            LOG.warn("Failed to restart Fluentd", e);
                                        }
                                    }

                                    fluencyCloseWaitLatch.countDown();
                                    try {
                                        assertTrue(fluencyCloseWaitLatch.await(20, TimeUnit.SECONDS));
                                    }
                                    catch (InterruptedException e) {
                                        LOG.warn("Interrupted", e);
                                    }
                                }
                            }
                            int tagNum = i % 4;
                            final String tag = String.format("foodb%d.bartbl%d", tagNum, tagNum);
                            switch (tagNum) {
                                case 0:
                                    tag0EventsCounter.incrementAndGet();
                                    break;
                                case 1:
                                    tag1EventsCounter.incrementAndGet();
                                    break;
                                case 2:
                                    tag2EventsCounter.incrementAndGet();
                                    break;
                                case 3:
                                    tag3EventsCounter.incrementAndGet();
                                    break;
                                default:
                                    throw new RuntimeException("Never reach here");
                            }

                            int rand = random.nextInt(maxNameLen);
                            final Map<String, Object> hashMap = new HashMap<String, Object>();
                            String name = nameLenTable.get(rand + 1);
                            nameEventsLength.addAndGet(name.length());
                            hashMap.put("name", name);
                            rand = random.nextInt(100);
                            int age = rand;
                            ageEventsSum.addAndGet(age);
                            hashMap.put("age", age);
                            hashMap.put("comment", "hello, world");
                            hashMap.put("rate", 1.23);

                            try {
                                Exception exception = null;
                                for (int retry = 0; retry < 10; retry++) {
                                    try {
                                        switch (options.emitType) {
                                            case MAP: {
                                                fluency.get().emit(tag, hashMap);
                                                break;
                                            }
                                            case MAP_WITH_EVENT_TIME: {
                                                EventTime eventTime = EventTime.fromEpochMilli(System.currentTimeMillis());
                                                fluency.get().emit(tag, eventTime, hashMap);
                                                break;
                                            }
                                            case MSGPACK_MAP_VALUE_BYTES: {
                                                byte[] bytes = objectMapper.writeValueAsBytes(hashMap);
                                                fluency.get().emit(tag, bytes, 0, bytes.length);
                                                break;
                                            }
                                            case MSGPACK_MAP_VALUE_BYTES_WITH_EVENT_TIME: {
                                                EventTime eventTime = EventTime.fromEpochMilli(System.currentTimeMillis());
                                                byte[] bytes = objectMapper.writeValueAsBytes(hashMap);
                                                fluency.get().emit(tag, eventTime, bytes, 0, bytes.length);
                                                break;
                                            }
                                            case MSGPACK_MAP_VALUE_BYTEBUFFER: {
                                                byte[] bytes = objectMapper.writeValueAsBytes(hashMap);
                                                fluency.get().emit(tag, ByteBuffer.wrap(bytes));
                                                break;
                                            }
                                            case MSGPACK_MAP_VALUE_BYTEBUFFER_WITH_EVENT_TIME: {
                                                EventTime eventTime = EventTime.fromEpochMilli(System.currentTimeMillis());
                                                byte[] bytes = objectMapper.writeValueAsBytes(hashMap);
                                                fluency.get().emit(tag, eventTime, ByteBuffer.wrap(bytes));
                                                break;
                                            }
                                        }
                                        exception = null;
                                        break;
                                    }
                                    catch (Exception e) {
                                        exception = e;
                                        try {
                                            TimeUnit.SECONDS.sleep(1);
                                        }
                                        catch (InterruptedException e1) {
                                        }
                                    }
                                }
                                if (exception != null) {
                                    throw exception;
                                }
                            }
                            catch (Exception e) {
                                LOG.warn("Exception occurred", e);
                            }
                        }
                        latch.countDown();
                    }
                });
            }

            for (int i = 0; i < 60; i++) {
                if (latch.await(1, TimeUnit.SECONDS)) {
                    break;
                }
            }
            assertEquals(0, latch.getCount());

            if (options.closeInsteadOfFlush) {
                fluency.get().close();
            }
            else {
                fluency.get().flush();
                fluency.get().waitUntilAllBufferFlushed(20);
            }

            fluentd.stop();
            secondaryFluentd.stop();
            TimeUnit.MILLISECONDS.sleep(1000);

            if (options.failover) {
                assertThat(fluentd.connectCounter.get(), is(greaterThan(0L)));
                assertThat(fluentd.connectCounter.get(), is(lessThanOrEqualTo(10L)));
                assertThat(fluentd.closeCounter.get(), is(greaterThan(0L)));
                assertThat(fluentd.closeCounter.get(), is(lessThanOrEqualTo(10L)));
            }
            else {
                assertThat(fluentd.connectCounter.get(), is(greaterThan(0L)));
                assertThat(fluentd.connectCounter.get(), is(lessThanOrEqualTo(2L)));
                assertThat(fluentd.closeCounter.get(), is(greaterThan(0L)));
                assertThat(fluentd.closeCounter.get(), is(lessThanOrEqualTo(2L)));
            }
            assertEquals((long) concurrency * reqNum, fluentd.ageEventsCounter.get());
            assertEquals(ageEventsSum.get(), fluentd.ageEventsSum.get());
            assertEquals((long) concurrency * reqNum, fluentd.nameEventsCounter.get());
            assertEquals(nameEventsLength.get(), fluentd.nameEventsLength.get());
            assertEquals(tag0EventsCounter.get(), fluentd.tag0EventsCounter.get());
            assertEquals(tag1EventsCounter.get(), fluentd.tag1EventsCounter.get());
            assertEquals(tag2EventsCounter.get(), fluentd.tag2EventsCounter.get());
            assertEquals(tag3EventsCounter.get(), fluentd.tag3EventsCounter.get());

            System.out.println(System.currentTimeMillis() - start);
        }
        finally {
            fluency.get().close();
            fluentd.stop();
            secondaryFluentd.stop();
        }
    }

    private static class MockFluentdServer
            extends AbstractFluentdServer
    {
        private final AtomicLong connectCounter;
        private final AtomicLong ageEventsCounter;
        private final AtomicLong ageEventsSum;
        private final AtomicLong nameEventsCounter;
        private final AtomicLong nameEventsLength;
        private final AtomicLong tag0EventsCounter;
        private final AtomicLong tag1EventsCounter;
        private final AtomicLong tag2EventsCounter;
        private final AtomicLong tag3EventsCounter;
        private final AtomicLong closeCounter;
        private final long startTimestampMillis;

        public MockFluentdServer()
                throws IOException
        {
            connectCounter = new AtomicLong();
            ageEventsCounter = new AtomicLong();
            ageEventsSum = new AtomicLong();
            nameEventsCounter = new AtomicLong();
            nameEventsLength = new AtomicLong();
            tag0EventsCounter = new AtomicLong();
            tag1EventsCounter = new AtomicLong();
            tag2EventsCounter = new AtomicLong();
            tag3EventsCounter = new AtomicLong();
            closeCounter = new AtomicLong();
            startTimestampMillis = System.currentTimeMillis();
        }

        public MockFluentdServer(MockFluentdServer base)
                throws IOException
        {
            connectCounter = base.connectCounter;
            ageEventsCounter = base.ageEventsCounter;
            ageEventsSum = base.ageEventsSum;
            nameEventsCounter = base.nameEventsCounter;
            nameEventsLength = base.nameEventsLength;
            tag0EventsCounter = base.tag0EventsCounter;
            tag1EventsCounter = base.tag1EventsCounter;
            tag2EventsCounter = base.tag2EventsCounter;
            tag3EventsCounter = base.tag3EventsCounter;
            closeCounter = base.closeCounter;
            startTimestampMillis = System.currentTimeMillis();
        }

        @Override
        protected EventHandler getFluentdEventHandler()
        {
            return new EventHandler()
            {
                @Override
                public void onConnect(SocketChannel accpetSocketChannel)
                {
                    connectCounter.incrementAndGet();
                }

                @Override
                public void onReceive(String tag, long timestampMillis, MapValue data)
                {
                    if (tag.equals("foodb0.bartbl0")) {
                        tag0EventsCounter.incrementAndGet();
                    }
                    else if (tag.equals("foodb1.bartbl1")) {
                        tag1EventsCounter.incrementAndGet();
                    }
                    else if (tag.equals("foodb2.bartbl2")) {
                        tag2EventsCounter.incrementAndGet();
                    }
                    else if (tag.equals("foodb3.bartbl3")) {
                        tag3EventsCounter.incrementAndGet();
                    }
                    else {
                        throw new IllegalArgumentException("Unexpected tag: tag=" + tag);
                    }
                    assertTrue(startTimestampMillis / 1000 <= timestampMillis / 1000 && timestampMillis < startTimestampMillis + 60 * 1000);

                    assertEquals(4, data.size());
                    for (Map.Entry<Value, Value> kv : data.entrySet()) {
                        String key = kv.getKey().asStringValue().toString();
                        Value val = kv.getValue();
                        if (key.equals("comment")) {
                            assertEquals("hello, world", val.toString());
                        }
                        else if (key.equals("rate")) {
                            assertEquals(1.23, val.asFloatValue().toFloat(), 0.000001);
                        }
                        else if (key.equals("name")) {
                            nameEventsCounter.incrementAndGet();
                            nameEventsLength.addAndGet(val.asRawValue().asString().length());
                        }
                        else if (key.equals("age")) {
                            ageEventsCounter.incrementAndGet();
                            ageEventsSum.addAndGet(val.asIntegerValue().asInt());
                        }
                    }
                }

                @Override
                public void onClose(SocketChannel accpetSocketChannel)
                {
                    closeCounter.incrementAndGet();
                }
            };
        }
    }

    private static class StuckSender
            extends StubSender
    {
        private final CountDownLatch latch;

        public StuckSender(CountDownLatch latch)
        {
            this.latch = latch;
        }

        @Override
        public void send(ByteBuffer data)
                throws IOException
        {
            try {
                latch.await();
            }
            catch (InterruptedException e) {
                FluencyTest.LOG.warn("Interrupted in send()", e);
            }
        }

        @Override
        public boolean isAvailable()
        {
            return true;
        }
    }

    @Test
    public void testBufferFullException()
            throws IOException
    {
        final CountDownLatch latch = new CountDownLatch(1);
        Sender stuckSender = new StuckSender(latch);

        try {
            PackedForwardBuffer.Config bufferConfig = new PackedForwardBuffer.Config().setChunkInitialSize(64).setMaxBufferSize(256);
            Fluency fluency = new Fluency.Builder(stuckSender).setBufferConfig(bufferConfig).build();
            Map<String, Object> event = new HashMap<String, Object>();
            event.put("name", "xxxx");
            for (int i = 0; i < 7; i++) {
                fluency.emit("tag", event);
            }
            try {
                fluency.emit("tag", event);
                assertTrue(false);
            }
            catch (BufferFullException e) {
                assertTrue(true);
            }
        }
        finally {
            latch.countDown();
        }
    }

    public static class Foo {
        public String s;
    }

    public static class FooSerializer extends StdSerializer<Foo> {
        public final AtomicBoolean serialized;

        protected FooSerializer(AtomicBoolean serialized)
        {
            super(Foo.class);
            this.serialized = serialized;
        }

        @Override
        public void serialize(Foo value, JsonGenerator gen, SerializerProvider provider)
                throws IOException
        {
            gen.writeStartObject();
            gen.writeStringField("s", "Foo:" + value.s);
            gen.writeEndObject();
            serialized.set(true);
        }
    }

    @Test
    public void testBufferWithJacksonModule()
            throws IOException
    {
        AtomicBoolean serialized = new AtomicBoolean();
        SimpleModule simpleModule = new SimpleModule();
        simpleModule.addSerializer(Foo.class, new FooSerializer(serialized));

        PackedForwardBuffer.Config bufferConfig = new PackedForwardBuffer
                .Config()
                .setChunkInitialSize(64)
                .setMaxBufferSize(256)
                .setJacksonModules(Collections.<Module>singletonList(simpleModule));

        Fluency fluency = new Fluency.Builder(new TCPSender.Config()
                .createInstance())
                .setBufferConfig(bufferConfig)
                .build();

        Map<String, Object> event = new HashMap<String, Object>();
        Foo foo = new Foo();
        foo.s = "Hello";
        event.put("foo", foo);
        fluency.emit("tag", event);

        assertThat(serialized.get(), is(true));
    }

}
