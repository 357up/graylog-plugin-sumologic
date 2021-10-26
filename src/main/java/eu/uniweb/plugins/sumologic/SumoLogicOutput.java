/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package eu.uniweb.plugins.sumologic;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import org.graylog2.plugin.outputs.MessageOutput;
import org.graylog2.plugin.outputs.MessageOutputConfigurationException;
import org.graylog2.plugin.streams.Stream;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.Message;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;

import com.sumologic.http.aggregation.SumoBufferFlusher;
import com.sumologic.http.queue.BufferWithEviction;
import com.sumologic.http.queue.BufferWithFifoEviction;
import com.sumologic.http.queue.CostBoundedConcurrentQueue;
import com.sumologic.http.sender.ProxySettings;
import com.sumologic.http.sender.SumoHttpSender;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;



/**
 * This is the plugin. Your class should implement one of the existing plugin
 * interfaces. (i.e. AlarmCallback, MessageInput, MessageOutput)
 */
public class SumoLogicOutput implements MessageOutput {
    /**
        Parameter	            Required?	Default Value	    Description
        encoder                 Yes		    ""                  Encoder to format the log message. This will usually specify a Pattern. For JsonLayout example, see this test appender.
        url                     Yes		    ""                  HTTP collection endpoint URL
        sourceName	            No	        "Http Input"	    Source name to appear when searching on Sumo Logic by _sourceName
        sourceHost	            No	        Client IP Address   Source host to appear when searching on Sumo Logic by _sourceHost
        sourceCategory	        No	        "Http Input"	    Source category to appear when searching on Sumo Logic by _sourceCategory
        proxyHost	            No		    ""                  Proxy host IP address
        proxyPort	            No		    ""                  Proxy host port number
        proxyAuth	            No		    ""                  For basic authentication proxy, set to "basic". For NTLM authentication proxy, set to "ntlm". For no authentication proxy, do not specify.
        proxyUser	            No		    ""                  Proxy host username for basic and NTLM authentication. For no authentication proxy, do not specify.
        proxyPassword	        No		    ""                  Proxy host password for basic and NTLM authentication. For no authentication proxy, do not specify.
        proxyDomain	            No		    ""                  Proxy host domain name for NTLM authentication only
        retryIntervalMs	        No	        10000	            Retry interval (in ms) if a request fails
        connectionTimeoutMs	    No	        1000	            Timeout (in ms) for connection
        socketTimeoutMs	        No	        60000	            Timeout (in ms) for a socket
        messagesPerRequest	    No	        100	                Number of messages needed to be in the queue before flushing
        maxFlushIntervalMs	    No	        10000	            Maximum interval (in ms) between flushes
        flushingAccuracyMs	    No	        250	                How often (in ms) that the flushing thread checks the message queue
        maxQueueSizeBytes	    No	        1000000	            Maximum capacity (in bytes) of the message queue
        flushAllBeforeStopping	No	        true	            Flush all messages before stopping regardless of flushingAccuracyMs Be sure to call loggerContext.stop(); when your application stops.
        retryableHttpCodeRegex	No	        ^5.*	            Regular expression specifying which HTTP error code(s) should be retried during sending. By default, all 5xx error codes will be retried.
        */

    private LayoutWrappingEncoder<ILoggingEvent> encoder = null;
    
    private static final String url = "URL";

    private static final String proxyHost = null;
    private static final String proxyPort = "-1";
    private static final String proxyAuth = null;
    private static final String proxyUser = null;
    private static final String proxyPassword = null;
    private static final String proxyDomain = null;

    private static final String connectionTimeoutMs = "1000";
    private static final String socketTimeoutMs = "60000";
    private static final String retryIntervalMs = "10000";        // Once a request fails, how often until we retry.
    private static final String flushAllBeforeStopping = "true"; // When true, perform a final flush on shutdown

    private static final String messagesPerRequest = "100";    // How many messages need to be in the queue before we flush
    private static final String maxFlushIntervalMs = "10000";    // Maximum interval between flushes (ms)
    private static final String flushingAccuracyMs = "250";      // How often the flusher thread looks into the message queue (ms)

    private static final String Name = "sourceName";
    private static final String Host = "sourceHost";
    private static final String Category = "sourceCategory";

    private static final String maxQueueSizeBytes = "1000000";

    private static final String retryableHttpCodeRegex = "^(4|5).*";

    private SumoHttpSender sender;
    private SumoBufferFlusher flusher;
    volatile private BufferWithEviction<String> queue;
    private static final String CLIENT_NAME = "logback-appender";

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public LayoutWrappingEncoder<ILoggingEvent> getEncoder() {
        return this.encoder;
    }

    public void setEncoder(LayoutWrappingEncoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    @Inject
    public SumoLogicOutput(@Assisted Configuration configuration) throws MessageOutputConfigurationException {
        // Check configuration.
        if (!checkConfiguration(configuration)) {
            throw new MessageOutputConfigurationException("Missing configuration.");
        }
        // Starting appender

        // Initialize queue
        if (queue == null) {
            queue = new BufferWithFifoEviction<String>(Long.valueOf(configuration.getInt(maxQueueSizeBytes)), new CostBoundedConcurrentQueue.CostAssigner<String>() {
                @Override
                public long cost(String e) {
                    // Note: This is only an estimate for total byte usage, since in UTF-8 encoding,
                    // the size of one character may be > 1 byte.
                    return e.length();
                }
            });
        } else {
            queue.setCapacity(Long.valueOf(configuration.getInt(maxQueueSizeBytes)));
        }

        // Initialize sender
        if (sender == null) {
            sender = new SumoHttpSender();
        }

        sender.setRetryIntervalMs(Long.valueOf(configuration.getInt(retryIntervalMs)));
        sender.setConnectionTimeoutMs(configuration.getInt(connectionTimeoutMs));
        sender.setSocketTimeoutMs(configuration.getInt(socketTimeoutMs));
        sender.setUrl(configuration.getString(url));
        sender.setSourceHost(configuration.getString(Host));
        sender.setSourceName(configuration.getString(Name));
        sender.setSourceCategory(configuration.getString(Category));
        sender.setProxySettings(new ProxySettings(
            proxyHost,
            Integer.parseInt(proxyPort),
            proxyAuth,
            proxyUser,
            proxyPassword,
            proxyDomain));
        sender.setClientHeaderValue(CLIENT_NAME);
        sender.setRetryableHttpCodeRegex(configuration.getString(retryableHttpCodeRegex));
        sender.init();

        // Initialize flusher
        if (flusher != null) {
            flusher.stop();
        }

        flusher = new SumoBufferFlusher(Long.valueOf(flushingAccuracyMs),
            Long.valueOf(messagesPerRequest),
            Long.valueOf(maxFlushIntervalMs),
            sender,
            queue,
            Boolean.parseBoolean(flushAllBeforeStopping));
        flusher.start();


        isRunning.set(true);
    }

    @Override
    public void write(Message message) throws Exception {
        if (message == null || message.getFields() == null || message.getFields().isEmpty()) {
            return;
        }

        try {
            queue.add(convertToString(message.getMessage()));
        } catch (Exception e) {
            System.err.println("Unable to insert log entry into log queue.", e);
        }
    
    }

    @Override
    public void write(List<Message> messages) throws Exception {
        for (Message m: messages) {
            queue.add(convertToString(m.getMessage()));
        }
    }
                /*  && c.stringIsSet(proxyHost)
                && c.intIsSet(proxyPort)
                && c.stringIsSet(proxyAuth)
                && c.stringIsSet(proxyUser)
                && c.stringIsSet(proxyPassword)
                && c.stringIsSet(proxyDomain)
                && c.stringIsSet(flushAllBeforeStopping) */

    public boolean checkConfiguration(Configuration c) {
        return c.stringIsSet(url)
                && c.stringIsSet(Name)
                && c.stringIsSet(Host)
                && c.stringIsSet(Category)
                && c.intIsSet(retryIntervalMs)
                && c.intIsSet(connectionTimeoutMs)
                && c.intIsSet(socketTimeoutMs)
                && c.intIsSet(messagesPerRequest)
                && c.intIsSet(maxFlushIntervalMs)
                && c.intIsSet(flushingAccuracyMs)
                && c.intIsSet(maxQueueSizeBytes)
                && c.stringIsSet(retryableHttpCodeRegex);
    }

    @Override
    public boolean isRunning() {
        return isRunning.get();
    }

    private String convertToString(ILoggingEvent event) {
        if (encoder.getCharset() == null) {
            return encoder.getLayout().doLayout(event);
        } else {
            return new String(encoder.encode(event), encoder.getCharset());
        }
    }

    @FactoryClass
    public interface Factory extends MessageOutput.Factory<SumoLogicOutput> {
        @Override
        SumoLogicOutput create(Stream stream, Configuration configuration);

        @Override
        Config getConfig();

        @Override
        Descriptor getDescriptor();
    }

    @Override
    public void stop() {
        try { 
            if (flusher != null) {
                flusher.stop();
                flusher = null;
            }
            if (sender != null) {
                sender.close();
                sender = null;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        isRunning.set(false);
    }

    @ConfigClass
    public static class Config extends MessageOutput.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest configurationRequest = new ConfigurationRequest();

            configurationRequest.addField(new TextField(
                url, "Sumo Logic Collection Endpoint URL", "",
                "HTTP collection endpoint URL",
                ConfigurationField.Optional.NOT_OPTIONAL)
            );

            configurationRequest.addField(new TextField(
                Name, "Source Name", "Http Input",
                "Source name to appear when searching on Sumo Logic by _sourceName",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new TextField(
                Host, "Client IP Address", "Client IP Address",
                "Source host to appear when searching on Sumo Logic by _sourceHost",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new TextField(
                Category, "Source Category", "Http Input",
                "Source category to appear when searching on Sumo Logic by _sourceCategory",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new NumberField(
                retryIntervalMs, "Retry Interval (Ms)", 10000,
                "Retry interval (in ms) if a request fails",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new NumberField(
                connectionTimeoutMs, "Connection Timeout (Ms)", 1000,
                "Timeout (in ms) for connection",
                ConfigurationField.Optional.OPTIONAL)
            );
            
            configurationRequest.addField(new NumberField(
                socketTimeoutMs, "Socket Timeout (Ms)", 60000,
                "Timeout (in ms) for a socket",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new NumberField(
                messagesPerRequest, "Messages per Request", 100,
                "Number of messages needed to be in the queue before flushing",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new NumberField(
                maxFlushIntervalMs, "Maximum Flush Interval (Ms)", 10000,
                "Maximum interval (in ms) between flushes",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new NumberField(
                flushingAccuracyMs, "Flushing Accuracy (Ms)", 250,
                "How often (in ms) that the flushing thread checks the message queue",
                ConfigurationField.Optional.OPTIONAL)
            );

            configurationRequest.addField(new NumberField(
                maxQueueSizeBytes, "Maximum message queue size (bytes)", 1000000,
                "Maximum capacity (in bytes) of the message queue",
                ConfigurationField.Optional.OPTIONAL)
            );

            /* final Map<String, String> forceFlush = ImmutableMap.of("true", "false");
            configurationRequest.addField(new DropdownField(
                flushAllBeforeStopping, "Flush Queue when Stopping", "true", forceFlush,
                "Flush all messages before stopping regardless of flushingAccuracyMs.",
                ConfigurationField.Optional.OPTIONAL)
            ); */

            configurationRequest.addField(new TextField(
                retryableHttpCodeRegex, "Retry on HTTP Status Codes (regex)", "^(4|5).*",
                "Regular expression specifying which HTTP error code(s) should be retried during sending. By default, all 5xx error codes will be retried.",
                ConfigurationField.Optional.OPTIONAL)
            );

            return configurationRequest;
        }
    }

    public static class Descriptor extends MessageOutput.Descriptor {
        public Descriptor() {
            super("Sumo Logic Output", false, "", "Outputs messages to Sumo Logc HTTP collection endpoint URL");
        }
    }

}
