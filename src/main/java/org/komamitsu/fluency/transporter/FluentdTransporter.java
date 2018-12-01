/*
 * Copyright 2018 Mitsunori Komatsu (komamitsu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.komamitsu.fluency.transporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.komamitsu.fluency.format.RequestOption;
import org.komamitsu.fluency.sender.FluentdSender;
import org.komamitsu.fluency.sender.Sender;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class FluentdTransporter
        implements Transporter
{
    private static final Logger LOG = LoggerFactory.getLogger(FluentdTransporter.class);
    private static final Charset CHARSET = Charset.forName("ASCII");
    private final Config config;
    private final Sender<? extends FluentdSender> sender;
    private final ObjectMapper objectMapper = new ObjectMapper(new MessagePackFactory());

    public FluentdTransporter(Config config, Sender<? extends FluentdSender> sender)
    {
        this.config = config;
        this.sender = sender;
    }

    @Override
    public void transport(String tag, ByteBuffer dataBuffer)
            throws IOException
    {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        MessagePacker messagePacker = MessagePack.newDefaultPacker(header);

        int dataLength = dataBuffer.limit();
        messagePacker.packArrayHeader(3);
        messagePacker.packString(tag);
        messagePacker.packRawStringHeader(dataLength);
        messagePacker.flush();

        ByteBuffer headerBuffer = ByteBuffer.wrap(header.toByteArray());

        if (config.isAckResponseMode()) {
            byte[] uuidBytes = UUID.randomUUID().toString().getBytes(CHARSET);
            ByteBuffer optionBuffer = ByteBuffer.wrap(objectMapper.writeValueAsBytes(new RequestOption(dataLength, uuidBytes)));
            List<ByteBuffer> buffers = Arrays.asList(headerBuffer, dataBuffer, optionBuffer);

            synchronized (sender) {
                sender.sendWithAck(buffers, uuidBytes);
            }
        } else {
            ByteBuffer optionBuffer = ByteBuffer.wrap(objectMapper.writeValueAsBytes(new RequestOption(dataLength, null)));
            List<ByteBuffer> buffers = Arrays.asList(headerBuffer, dataBuffer, optionBuffer);

            synchronized (sender) {
                sender.send(buffers);
            }
        }
    }

    public boolean isAckResponseMode()
    {
        return config.isAckResponseMode();
    }

    @Override
    public void close()
            throws IOException
    {
        sender.close();
    }

    public static class Config
        implements Instantiator<FluentdSender>
    {
        private boolean ackResponseMode = false;

        public boolean isAckResponseMode()
        {
            return ackResponseMode;
        }

        public Config setAckResponseMode(boolean ackResponseMode)
        {
            this.ackResponseMode = ackResponseMode;
            return this;
        }

        @Override
        public Transporter createInstance(Sender<FluentdSender> sender)
        {
            return new FluentdTransporter(this, sender);
        }
    }
}
