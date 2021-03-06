/*
 * Copyright 2019 Mitsunori Komatsu (komamitsu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.komamitsu.fluency.fluentd.ingester.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.komamitsu.fluency.fluentd.ingester.Response;
import org.komamitsu.fluency.fluentd.ingester.sender.failuredetect.FailureDetectStrategy;
import org.komamitsu.fluency.fluentd.ingester.sender.failuredetect.FailureDetector;
import org.komamitsu.fluency.fluentd.ingester.sender.failuredetect.PhiAccrualFailureDetectStrategy;
import org.komamitsu.fluency.fluentd.ingester.sender.heartbeat.Heartbeater;
import org.komamitsu.fluency.ingester.sender.ErrorHandler;
import org.komamitsu.fluency.util.ExecutorServiceUtils;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public abstract class NetworkSender<T>
    extends FluentdSender
{
    private static final Logger LOG = LoggerFactory.getLogger(NetworkSender.class);
    private static final Charset CHARSET_FOR_ERRORLOG = Charset.forName("UTF-8");
    private final byte[] optionBuffer = new byte[256];
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Config config;
    private final FailureDetector failureDetector;
    private final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

    NetworkSender(Config config)
    {
        super(config.getBaseConfig());
        this.config = config;
        FailureDetector failureDetector = null;
        if (config.getHeartbeaterConfig() != null) {
            try {
                failureDetector = new FailureDetector(
                        config.getFailureDetectorStrategyConfig(),
                        config.getHeartbeaterConfig(),
                        config.getFailureDetectorConfig());
            }
            catch (IOException e) {
                LOG.warn("Failed to instantiate FailureDetector. Disabling it", e);
            }
        }
        this.failureDetector = failureDetector;
    }

    @Override
    public boolean isAvailable()
    {
        return failureDetector == null || failureDetector.isAvailable();
    }

    abstract T getOrCreateSocketInternal()
        throws IOException;

    private synchronized T getOrCreateSocket()
            throws IOException
    {
        return getOrCreateSocketInternal();
    }

    abstract void sendBuffers(T socket, List<ByteBuffer> buffers)
            throws IOException;

    abstract void recvResponse(T socket, ByteBuffer buffer)
            throws IOException;

    private void propagateFailure(Throwable e)
    {
        if (failureDetector != null) {
            failureDetector.onFailure(e);
        }
    }

    @Override
    protected synchronized void sendInternal(List<ByteBuffer> buffers, byte[] ackToken)
            throws IOException
    {
        long totalDataSize = buffers.stream().mapToInt(ByteBuffer::remaining).sum();

        try {
            LOG.trace("send(): sender.host={}, sender.port={}, totalDataSize={}",
                    getHost(), getPort(), totalDataSize);
            final T socket = getOrCreateSocket();
            sendBuffers(socket, buffers);

            if (ackToken == null) {
                return;
            }

            // For ACK response mode
            final ByteBuffer byteBuffer = ByteBuffer.wrap(optionBuffer);

            Future<Void> future = executorService.submit(new Callable<Void>()
            {
                @Override
                public Void call()
                        throws Exception
                {
                    LOG.trace("recv(): sender.host={}, sender.port={}", getHost(), getPort());
                    recvResponse(socket, byteBuffer);
                    return null;
                }
            });

            try {
                future.get(config.getReadTimeoutMilli(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                throw new IOException("InterruptedException occurred", e);
            }
            catch (ExecutionException e) {
                throw new IOException("ExecutionException occurred", e);
            }
            catch (TimeoutException e) {
                throw new SocketTimeoutException("Socket read timeout");
            }

            Response response = objectMapper.readValue(optionBuffer, Response.class);
            byte[] unpackedToken = response.getAck();
            if (!Arrays.equals(ackToken, unpackedToken)) {
                throw new UnmatchedAckException("Ack tokens don't matched: expected=" + new String(ackToken, CHARSET_FOR_ERRORLOG) + ", got=" + new String(unpackedToken, CHARSET_FOR_ERRORLOG));
            }
        }
        catch (IOException e) {
            LOG.error("Failed to send {} bytes data", totalDataSize);
            closeSocket();
            propagateFailure(e);
            throw e;
        }
    }

    abstract void closeSocket()
            throws IOException;

    @Override
    public synchronized void close()
            throws IOException
    {
        try {
            // Wait to confirm unsent request is flushed
            try {
                TimeUnit.MILLISECONDS.sleep(config.getWaitBeforeCloseMilli());
            }
            catch (InterruptedException e) {
                LOG.warn("Interrupted", e);
                Thread.currentThread().interrupt();
            }

            closeSocket();
        }
        finally {
            try {
                if (failureDetector != null) {
                    failureDetector.close();
                }
            }
            finally {
                ExecutorServiceUtils.finishExecutorService(executorService);
            }
        }
    }

    public static class UnmatchedAckException
            extends IOException
    {
        public UnmatchedAckException(String message)
        {
            super(message);
        }
    }

    public String getHost()
    {
        return config.getHost();
    }

    public int getPort()
    {
        return config.getPort();
    }

    public int getConnectionTimeoutMilli()
    {
        return config.getConnectionTimeoutMilli();
    }

    public int getReadTimeoutMilli()
    {
        return config.getReadTimeoutMilli();
    }

    public FailureDetector getFailureDetector()
    {
        return failureDetector;
    }

    @Override
    public String toString()
    {
        return "NetworkSender{" +
                "config=" + config +
                ", failureDetector=" + failureDetector +
                "} " + super.toString();
    }

    public static class Config
    {
        private final FluentdSender.Config baseConfig = new FluentdSender.Config();
        private String host = "127.0.0.1";
        private int port = 24224;
        private int connectionTimeoutMilli = 5000;
        private int readTimeoutMilli = 5000;
        private Heartbeater.Instantiator heartbeaterConfig;   // Disabled by default
        private FailureDetector.Config failureDetectorConfig = new FailureDetector.Config();
        private FailureDetectStrategy.Instantiator failureDetectorStrategyConfig = new PhiAccrualFailureDetectStrategy.Config();
        private int waitBeforeCloseMilli = 1000;

        public FluentdSender.Config getBaseConfig()
        {
            return baseConfig;
        }

        public ErrorHandler getErrorHandler()
        {
            return baseConfig.getErrorHandler();
        }

        public Config setErrorHandler(ErrorHandler errorHandler)
        {
            baseConfig.setErrorHandler(errorHandler);
            return this;
        }

        public String getHost()
        {
            return host;
        }

        public Config setHost(String host)
        {
            this.host = host;
            return this;
        }

        public int getPort()
        {
            return port;
        }

        public Config setPort(int port)
        {
            this.port = port;
            return this;
        }

        public int getConnectionTimeoutMilli()
        {
            return connectionTimeoutMilli;
        }

        public Config setConnectionTimeoutMilli(int connectionTimeoutMilli)
        {
            this.connectionTimeoutMilli = connectionTimeoutMilli;
            return this;
        }

        public int getReadTimeoutMilli()
        {
            return readTimeoutMilli;
        }

        public Config setReadTimeoutMilli(int readTimeoutMilli)
        {
            this.readTimeoutMilli = readTimeoutMilli;
            return this;
        }

        public Heartbeater.Instantiator getHeartbeaterConfig()
        {
            return heartbeaterConfig;
        }

        public Config setHeartbeaterConfig(Heartbeater.Instantiator heartbeaterConfig)
        {
            this.heartbeaterConfig = heartbeaterConfig;
            return this;
        }

        public FailureDetector.Config getFailureDetectorConfig()
        {
            return failureDetectorConfig;
        }

        public Config setFailureDetectorConfig(FailureDetector.Config failureDetectorConfig)
        {
            this.failureDetectorConfig = failureDetectorConfig;
            return this;
        }

        public FailureDetectStrategy.Instantiator getFailureDetectorStrategyConfig()
        {
            return failureDetectorStrategyConfig;
        }

        public Config setFailureDetectorStrategyConfig(FailureDetectStrategy.Instantiator failureDetectorStrategyConfig)
        {
            this.failureDetectorStrategyConfig = failureDetectorStrategyConfig;
            return this;
        }

        public int getWaitBeforeCloseMilli()
        {
            return waitBeforeCloseMilli;
        }

        public Config setWaitBeforeCloseMilli(int waitBeforeCloseMilli)
        {
            this.waitBeforeCloseMilli = waitBeforeCloseMilli;
            return this;
        }

        @Override
        public String toString()
        {
            return "Config{" +
                    "baseConfig=" + baseConfig +
                    ", host='" + host + '\'' +
                    ", port=" + port +
                    ", connectionTimeoutMilli=" + connectionTimeoutMilli +
                    ", readTimeoutMilli=" + readTimeoutMilli +
                    ", heartbeaterConfig=" + heartbeaterConfig +
                    ", failureDetectorConfig=" + failureDetectorConfig +
                    ", failureDetectorStrategyConfig=" + failureDetectorStrategyConfig +
                    ", waitBeforeCloseMilli=" + waitBeforeCloseMilli +
                    '}';
        }
    }
}
