// Copyright (c) 2007-2023 VMware, Inc. or its affiliates.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 2.0 ("MPL"), the GNU General Public License version 2
// ("GPL") and the Apache License version 2 ("ASL"). For the MPL, please see
// LICENSE-MPL-RabbitMQ. For the GPL, please see LICENSE-GPL2.  For the ASL,
// please see LICENSE-APACHE2.
//
// This software is distributed on an "AS IS" basis, WITHOUT WARRANTY OF ANY KIND,
// either express or implied. See the LICENSE file for specific language governing
// rights and limitations of this software.
//
// If you have any questions regarding licensing, please contact us at
// info@rabbitmq.com.

package com.rabbitmq.perf;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultSaslConfig;
import com.rabbitmq.client.ExceptionHandler;
import com.rabbitmq.client.RecoveryDelayHandler;
import com.rabbitmq.client.impl.ClientVersion;
import com.rabbitmq.client.impl.DefaultExceptionHandler;
import com.rabbitmq.client.impl.nio.NioParams;
import com.rabbitmq.perf.Metrics.ConfigurationContext;
import com.rabbitmq.perf.metrics.CompositeMetricsFormatter;
import com.rabbitmq.perf.metrics.CsvMetricsFormatter;
import com.rabbitmq.perf.metrics.DefaultPerformanceMetrics;
import com.rabbitmq.perf.metrics.MetricsFormatter;
import com.rabbitmq.perf.metrics.MetricsFormatterFactory;
import com.rabbitmq.perf.metrics.MetricsFormatterFactory.Context;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.*;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

import static com.rabbitmq.perf.OptionsUtils.forEach;
import static com.rabbitmq.perf.Utils.strArg;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;

public class PerfTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerfTest.class);

    public static void main(String [] args, PerfTestOptions perfTestOptions) {
        SystemExiter systemExiter = perfTestOptions.systemExiter;
        ShutdownService shutdownService = perfTestOptions.shutdownService;
        PrintStream consoleOut = perfTestOptions.consoleOut;
        PrintStream consoleErr = perfTestOptions.consoleErr;
        Options options = getOptions();
        CommandLineParser parser = getParser();
        CompositeMetrics metrics = new CompositeMetrics();
        shutdownService.wrap(() -> metrics.close());
        Options metricsOptions = metrics.options();
        forEach(metricsOptions, option -> options.addOption(option));
        int exitStatus = 0;

        try {
            CommandLine rawCmd = parser.parse(options, args);
            CommandLineProxy cmd = new CommandLineProxy(options, rawCmd, perfTestOptions.argumentLookup);

            if (cmd.hasOption("mh")) {
                if (cmd.hasOption("env")) {
                    usageWithEnvironmentVariables(metricsOptions);
                } else {
                    usage(metricsOptions);
                }
                systemExiter.exit(0);
            }

            if(cmd.hasOption("env")) {
                usageWithEnvironmentVariables(getOptions());
                systemExiter.exit(0);
            }

            if (cmd.hasOption('?')) {
                usage(getOptions());
                systemExiter.exit(0);
            }

            if (cmd.hasOption('v')) {
                versionInformation();
                systemExiter.exit(0);
            }

            int expectedInstances = intArg(cmd, "ei", 0);
            String testID                   = strArg(cmd, 'd', null);
            if (expectedInstances >= 2 && testID == null) {
               validate(() -> false, "A test ID is mandatory when "
                       + "instance synchronization is activated",
                   systemExiter, consoleErr);
            }

            testID = new SimpleDateFormat("HHmmss-SSS").format(Calendar.
                getInstance().getTime());
            testID                   = strArg(cmd, 'd', "test-"+testID);
            boolean saslExternal     = hasOption(cmd, "se");
            boolean disableConnectionRecovery = hasOption(cmd, "dcr");
            String uri               = strArg(cmd, 'h', "amqp://localhost");
            String urisParameter     = strArg(cmd, 'H', null);
            int frameMax             = intArg(cmd, 'M', 0);
            int heartbeat            = intArg(cmd, 'b', 0);
            boolean useMillis        = hasOption(cmd,"ms");
            int samplingInterval     = intArg(cmd, 'i', 1);
            String metricsFormat = strArg(cmd, "mf", "default");
            String outputFile        = strArg(cmd, 'o', null);
            String metricsPrefix = strArg(cmd, "mpx", "perftest_");

            CompositeMeterRegistry registry = new CompositeMeterRegistry();
            shutdownService.wrap(() -> registry.close());

            List<String> uris;
            if(urisParameter != null) {
                String [] urisArray = urisParameter.split(",");
                for(int i = 0; i< urisArray.length; i++) {
                    urisArray[i] = urisArray[i].trim();
                }
                uris = asList(urisArray);
            } else {
                uris = singletonList(uri);
            }
            ConnectionFactory factory = new ConnectionFactory();
            factory.setTopologyRecoveryEnabled(false);
            if (disableConnectionRecovery) {
                factory.setAutomaticRecoveryEnabled(false);
            }
            RecoveryDelayHandler recoveryDelayHandler = Utils.getRecoveryDelayHandler(
                strArg(cmd, "cri", null));
            if (recoveryDelayHandler != null) {
                factory.setRecoveryDelayHandler(recoveryDelayHandler);
            }
            SSLContext sslContext = perfTestOptions.skipSslContextConfiguration ? null :
                getSslContextIfNecessary(cmd, System.getProperties());

            if (sslContext != null) {
                factory.useSslProtocol(sslContext);
            }
            if (saslExternal) {
                factory.setSaslConfig(DefaultSaslConfig.EXTERNAL);
            }
            factory.setShutdownTimeout(0); // So we still shut down even with slow consumers
            factory.setUri(uris.get(0));
            factory.setRequestedFrameMax(frameMax);
            factory.setRequestedHeartbeat(heartbeat);
            factory = configureNioIfRequested(cmd, factory);
            if (factory.getNioParams().getNioExecutor() != null) {
                ExecutorService nioExecutor = factory.getNioParams().getNioExecutor();
                shutdownService.wrap(() -> nioExecutor.shutdownNow());
            }

            factory.setSocketConfigurator(Utils.socketConfigurator(cmd));
            if (factory.getNioParams() != null) {
                factory.getNioParams().setSslEngineConfigurator(Utils.sslEngineConfigurator(cmd));
            }

            metrics.configure(new ConfigurationContext(cmd, registry, factory, args,
                metricsPrefix, metricsOptions));

            MulticastParams p = multicastParams(cmd, uris, perfTestOptions);

            ConcurrentMap<String, Integer> completionReasons = new ConcurrentHashMap<>();

            MulticastSet.CompletionHandler completionHandler = getCompletionHandler(p, completionReasons);

            factory.setExceptionHandler(perfTestOptions.exceptionHandler);

            TimeUnit latencyCollectionTimeUnit = useMillis ? TimeUnit.MILLISECONDS : TimeUnit.NANOSECONDS;

            int producerCount = p.getProducerCount();
            int consumerCount = p.getConsumerCount();
            long confirm = p.getConfirm();
            List<String> flags = p.getFlags();
            MetricsFormatter metricsFormatter = null;
            try {
                metricsFormatter = MetricsFormatterFactory.create(metricsFormat,
                    new Context(consoleOut,
                        testID,
                        producerCount > 0,
                        consumerCount > 0,
                        (flags.contains("mandatory") || flags.contains("immediate")),
                        confirm != -1,
                        latencyCollectionTimeUnit)
                );
            } catch (IllegalArgumentException e) {
                consoleErr.println(e.getMessage());
                systemExiter.exit(1);
            }

            PrintWriter output;
            if (outputFile != null) {
                output = openCsvFileForWriting(outputFile, shutdownService);
            } else {
                output = null;
            }
            if (output != null) {
                metricsFormatter = new CompositeMetricsFormatter(
                    metricsFormatter,
                    new CsvMetricsFormatter(output, testID, producerCount > 0,
                        consumerCount > 0,
                        (flags.contains("mandatory") || flags.contains("immediate")),
                        confirm != -1,
                        latencyCollectionTimeUnit)
                );
            }
            DefaultPerformanceMetrics performanceMetrics = new DefaultPerformanceMetrics(
                Duration.ofSeconds(samplingInterval),
                latencyCollectionTimeUnit,
                registry, metricsPrefix, metricsFormatter);

            AtomicBoolean statsSummaryDone = new AtomicBoolean(false);
            Runnable statsSummary = () -> {
                if (statsSummaryDone.compareAndSet(false, true)) {
                    consoleOut.println(stopLine(completionReasons));
                    performanceMetrics.close();
                    consoleOut.flush();
                }
            };
            shutdownService.wrap(() -> statsSummary.run());

            Map<String, Object> exposedMetrics = convertKeyValuePairs(strArg(cmd, "em", null));
            ExpectedMetrics expectedMetrics = new ExpectedMetrics(p, registry, metricsPrefix, exposedMetrics);
            int agentCount = p.getProducerThreadCount() + p.getConsumerThreadCount();
            Set<Integer> starts = ConcurrentHashMap.newKeySet(agentCount);
            p.setStartListener((id, type) -> {
                if (starts.add(id) && starts.size() == agentCount) {
                    performanceMetrics.resetGlobals();
                }
                expectedMetrics.agentStarted(type);
            });

            String instanceSyncNamespace = lookUpInstanceSyncNamespace(cmd);
            int instanceSyncTimeout = intArg(cmd, "ist", 600);
            InstanceSynchronization instanceSynchronization = new DefaultInstanceSynchronization(
                testID, expectedInstances, instanceSyncNamespace, Duration.ofSeconds(instanceSyncTimeout),
                consoleOut
            );
            instanceSynchronization.addPostSyncListener(() -> metrics.start());

            MulticastSet set = new MulticastSet(performanceMetrics, factory, p, testID, uris, completionHandler,
                shutdownService, expectedMetrics, instanceSynchronization);
            set.run(true);

            statsSummary.run();
        } catch (ParseException exp) {
            consoleErr.println("Parsing failed. Reason: " + exp.getMessage());
            usage(options);
        } catch (Exception e) {
            consoleErr.println("Main thread caught exception: " + e);
            LOGGER.error("Main thread caught exception", e);
            exitStatus = 1;
        } finally {
            shutdownService.close();
        }
        // we need to exit explicitly, without waiting alive threads (e.g. when using --shutdown-timeout 0)
        systemExiter.exit(exitStatus);
    }

    static MulticastParams multicastParams(CommandLineProxy cmd, List<String> uris, PerfTestOptions perfTestOptions) throws Exception {
        SystemExiter systemExiter = perfTestOptions.systemExiter;
        PrintStream consoleErr = perfTestOptions.consoleErr;

        String exchangeType      = strArg(cmd, 't', "direct");
        String exchangeName      = getExchangeName(cmd, exchangeType);
        String queueNames        = strArg(cmd, 'u', null);
        String routingKey        = strArg(cmd, 'k', null);
        boolean randomRoutingKey = hasOption(cmd, "K");
        boolean skipBindingQueues= hasOption(cmd,"sb");
        float producerRateLimit  = floatArg(cmd, 'r', -1.0f);
        float consumerRateLimit  = floatArg(cmd, 'R', -1.0f);
        int producerCount        = intArg(cmd, 'x', 1);
        int consumerCount        = intArg(cmd, 'y', 1);
        int producerChannelCount = intArg(cmd, 'X', 1);
        int consumerChannelCount = intArg(cmd, 'Y', 1);
        int producerTxSize       = intArg(cmd, 'm', 0);
        int consumerTxSize       = intArg(cmd, 'n', 0);
        long confirm             = intArg(cmd, 'c', -1);
        int confirmTimeout       = intArg(cmd, "ct", 30);
        boolean autoAck          = hasOption(cmd,"a");
        int multiAckEvery        = intArg(cmd, 'A', 0);
        int channelPrefetch      = intArg(cmd, 'Q', 0);
        int consumerPrefetch     = intArg(cmd, 'q', 0);
        int minMsgSize           = intArg(cmd, 's', 0);
        boolean slowStart        = hasOption(cmd, "S");
        int timeLimit            = intArg(cmd, 'z', 0);
        int producerMsgCount     = intArg(cmd, 'C', 0);
        int consumerMsgCount     = intArg(cmd, 'D', 0);
        List<String> flags       = lstArg(cmd, 'f');
        String bodyFiles         = strArg(cmd, 'B', null);
        String bodyContentType   = strArg(cmd, 'T', null);
        boolean predeclared      = hasOption(cmd, "p");
        boolean autoDelete       = boolArg(cmd, "ad", true);
        boolean useMillis        = hasOption(cmd,"ms");
        List<String> queueArgs   = lstArg(cmd, "qa");
        int consumerLatencyInMicroseconds = intArg(cmd, 'L', 0);
        int heartbeatSenderThreads = intArg(cmd, "hst", -1);
        String messageProperties = strArg(cmd, "mp", null);
        int routingKeyCacheSize  = intArg(cmd, "rkcs", 0);
        boolean exclusive = hasOption(cmd, "E");
        Duration publishingInterval = null;
        String publishingIntervalArg = strArg(cmd, "P", null);
        if (publishingIntervalArg != null) {
            try {
                publishingInterval = parsePublishingInterval(publishingIntervalArg);
            } catch (IllegalArgumentException e) {
                consoleErr.println("Invalid value for --publishing-interval: " + e.getMessage());
                systemExiter.exit(1);
            }
        }
        int producerRandomStartDelayInSeconds = intArg(cmd, "prsd", -1);
        int producerSchedulingThreads = intArg(cmd, "pst", -1);

        int consumersThreadPools = intArg(cmd, "ctp", -1);
        int shutdownTimeout = intArg(cmd, "st", 5);

        int serversStartUpTimeout = intArg(cmd, "sst", -1);
        int serversUpLimit = intArg(cmd, "sul", -1);
        String consumerArgs = strArg(cmd, "ca", null);

        List<String> variableRates = lstArg(cmd, "vr");
        if (variableRates != null && !variableRates.isEmpty()) {
            for (String variableRate : variableRates) {
                try {
                    VariableValueIndicator.validate(variableRate);
                } catch (IllegalArgumentException e) {
                    consoleErr.println(e.getMessage());
                    systemExiter.exit(1);
                }
            }
        }

        if ((!variableRates.isEmpty() || producerRateLimit >= 0 || publishingInterval != null)
            && producerRandomStartDelayInSeconds < 0) {
            // producer rate instructions, but no ramp-up period, so setting it to 1 second
            producerRandomStartDelayInSeconds = 1;
        }

        List<String> variableSizes = lstArg(cmd, "vs");
        if (variableSizes != null && !variableSizes.isEmpty()) {
            for (String variableSize : variableSizes) {
                try {
                    VariableValueIndicator.validate(variableSize);
                } catch (IllegalArgumentException e) {
                    consoleErr.println(e.getMessage());
                    systemExiter.exit(1);
                }
            }
        }

        List<String> variableConsumerLatencies = lstArg(cmd, "vl");
        if (variableConsumerLatencies != null && !variableConsumerLatencies.isEmpty()) {
            for (String variableConsumerLatency : variableConsumerLatencies) {
                try {
                    VariableValueIndicator.validate(variableConsumerLatency);
                } catch (IllegalArgumentException e) {
                    consoleErr.println(e.getMessage());
                    systemExiter.exit(1);
                }
            }
        }

        boolean polling = hasOption(cmd, "po");
        int pollingInterval = intArg(cmd, "pi", -1);

        boolean nack = hasOption(cmd, "na");
        boolean requeue = boolArg(cmd, "re", true);

        boolean jsonBody = hasOption(cmd, "jb");
        int bodyFieldCount = intArg(cmd, "bfc", 1000);
        if (bodyFieldCount < 0) {
            consoleErr.println("Body field count should greater than 0.");
            systemExiter.exit(1);
        }
        int bodyCount = intArg(cmd, "bc", 100);
        if (bodyCount < 0) {
            consoleErr.println("Number of pre-generated message bodies should be greater than 0.");
            systemExiter.exit(1);
        }

        Map<String, Object> queueArguments = convertKeyValuePairs(queueArgs);
        queueArguments = queueArguments == null ? new LinkedHashMap<>() : queueArguments;
        boolean quorumQueue = hasOption(cmd,"qq");
        boolean streamQueue = hasOption(cmd,"sq");

        validate(() -> !(quorumQueue && streamQueue),
            "Use quorum queues or stream queues, not both.",
            systemExiter,
            consoleErr);

        if (quorumQueue || streamQueue) {
            if (!flags.contains("persistent")) {
                flags = new ArrayList<>(flags);
                flags.add("persistent");
            }
            autoDelete = false;
            String type = quorumQueue ? "quorum" : "stream";
            queueArguments.put("x-queue-type", type);
        }

        ByteCapacity maxLengthByteCapacity = null;
        if (streamQueue) {
            consumerPrefetch = 200;
            maxLengthByteCapacity = ByteCapacity.GB(20);
            queueArguments.put("x-max-length-bytes", maxLengthByteCapacity.toBytes());
            queueArguments.put("x-stream-max-segment-size-bytes", ByteCapacity.from("500mb").toBytes());
        }

        String exitWhenParameter = strArg(cmd, "ew", null);
        EXIT_WHEN exitWhen;
        if (exitWhenParameter != null) {
            if (!"empty".equals(exitWhenParameter) && !"idle".equals(exitWhenParameter)) {
                consoleErr.println("--exit-when must be 'empty' or 'idle'.");
                systemExiter.exit(1);
            }
            exitWhen = EXIT_WHEN.valueOf(exitWhenParameter.toUpperCase(Locale.ENGLISH));
        } else {
            exitWhen = EXIT_WHEN.NEVER;
        }
        Duration consumerStartDelay = Duration.ofSeconds(intArg(cmd, "csd", -1));

        String queuePattern        = strArg(cmd, "qp", null);
        int from                   = intArg(cmd, "qpf", -1);
        int to                     = intArg(cmd, "qpt", -1);

        if (queuePattern != null || from >= 0 || to >= 0) {
            if (queuePattern == null || from < 0 || to < 0) {
                consoleErr.println("Queue pattern, from, and to options should all be set or none should be set");
                systemExiter.exit(1);
            }
            if (from > to) {
                consoleErr.println("'To' option should be more than or equals to 'from' option");
                systemExiter.exit(1);
            }
        }

        List<String> queues = queueNames == null ? null : asList(queueNames.split(","));

        String queueFile = strArg(cmd, "qf", null);
        if (queueFile != null && queuePattern != null && queueNames != null) {
            consoleErr.println("Too many ways to list queues, use only the queue file argument");
            systemExiter.exit(1);
        } else if (queueFile != null) {
            File file = new File(queueFile);
            if (!file.exists() || !file.canRead()) {
                consoleErr.println("Queue file " + queueFile + " does not exist or is not readable");
                systemExiter.exit(1);
            }
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                queues = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    queues.add(line.trim());
                }
            }
        }

        String maxLengthBytes = strArg(cmd, "mlb", null);
        if (maxLengthBytes != null) {
            ByteCapacity byteCapacity = validateByteCapacity(maxLengthBytes, systemExiter, consoleErr);
            if (byteCapacity != null) {
                long bytes = byteCapacity.toBytes();
                if (bytes == 0) {
                    queueArguments.remove("x-max-length-bytes");
                    maxLengthByteCapacity = null;
                } else {
                    queueArguments.put("x-max-length-bytes", bytes);
                    maxLengthByteCapacity = byteCapacity;
                }
            }
        }
        String streamMaxSegmentSize = strArg(cmd, "smssb", null);
        ByteCapacity streamMaxSegmentByteCapacity = null;
        if (streamMaxSegmentSize != null) {
            ByteCapacity byteCapacity = validateByteCapacity(streamMaxSegmentSize, systemExiter, consoleErr);
            if (byteCapacity != null) {
                validate(() -> byteCapacity.compareTo(ByteCapacity.GB(3)) <= 0,
                    "The maximum segment size cannot be more than 3 GB",
                    systemExiter, consoleErr);
                long bytes = byteCapacity.toBytes();
                queueArguments.put("x-stream-max-segment-size-bytes", bytes);
                streamMaxSegmentByteCapacity = byteCapacity;
            }
        }
        String maxAge = strArg(cmd, "ma", null);
        if (maxAge != null) {
           Duration duration = validateDuration(maxAge, systemExiter, consoleErr);
           if (duration != null) {
              queueArguments.put("x-max-age", duration.getSeconds() + "s");
           }
        }

        String queueLeaderLocator = strArg(cmd, "ll", null);
        if (queueLeaderLocator != null) {
            validate(() -> "client-local".equals(queueLeaderLocator) || "balanced".equals(queueLeaderLocator),
                "'" + queueLeaderLocator + "' is not a valid queue leader locator strategy. "
                    + "Valid values are client-local and balanced.",
                systemExiter, consoleErr);
            queueArguments.put("x-queue-leader-locator", queueLeaderLocator);
        }

        Map<String, Object> consumerArguments = convertKeyValuePairs(consumerArgs);

        String streamConsumerOffset = strArg(cmd, "sco", null);
        if (streamConsumerOffset != null) {
            Object offset = validateStreamConsumerOffset(streamConsumerOffset,
                systemExiter, consoleErr);
            if (offset != null) {
                consumerArguments = consumerArguments == null ? new LinkedHashMap<>() :
                    consumerArguments;
                consumerArguments.put("x-stream-offset", offset);
            }
        }

        if (maxLengthByteCapacity != null && streamMaxSegmentByteCapacity != null) {
            if (maxLengthByteCapacity.compareTo(streamMaxSegmentByteCapacity) <= 0) {
                validate(() -> Boolean.FALSE, "Max length bytes must be greather than "
                    + "stream max segment size", systemExiter, consoleErr);
            }
        }

        MulticastParams p = new MulticastParams();
        p.setAutoAck(               autoAck);
        p.setAutoDelete(            autoDelete);
        p.setConfirm(               confirm);
        p.setConfirmTimeout(        confirmTimeout);
        p.setConsumerCount(         consumerCount);
        p.setConsumerChannelCount(  consumerChannelCount);
        p.setConsumerMsgCount(      consumerMsgCount);
        p.setConsumerRateLimit(     consumerRateLimit);
        p.setConsumerTxSize(        consumerTxSize);
        p.setConsumerSlowStart(     slowStart);
        p.setExchangeName(          exchangeName);
        p.setExchangeType(          exchangeType);
        p.setFlags(                 flags);
        p.setMultiAckEvery(         multiAckEvery);
        p.setMinMsgSize(            minMsgSize);
        p.setPredeclared(           predeclared);
        p.setConsumerPrefetch(      consumerPrefetch);
        p.setChannelPrefetch(       channelPrefetch);
        p.setProducerCount(         producerCount);
        p.setProducerChannelCount(  producerChannelCount);
        p.setProducerMsgCount(      producerMsgCount);
        p.setProducerTxSize(        producerTxSize);
        p.setQueueNames(            queues);
        p.setRoutingKey(            routingKey);
        p.setSkipBindingQueues(     skipBindingQueues);
        p.setRandomRoutingKey(      randomRoutingKey);
        p.setProducerRateLimit(     producerRateLimit);
        p.setTimeLimit(             timeLimit);
        p.setUseMillis(             useMillis);
        p.setBodyFiles(             bodyFiles == null ? null : asList(bodyFiles.split(",")));
        p.setBodyContentType(       bodyContentType);
        p.setQueueArguments(queueArguments);
        p.setConsumerLatencyInMicroseconds(consumerLatencyInMicroseconds);
        p.setConsumerLatencies(variableConsumerLatencies);
        p.setQueuePattern(queuePattern);
        p.setQueueSequenceFrom(from);
        p.setQueueSequenceTo(to);
        p.setHeartbeatSenderThreads(heartbeatSenderThreads);
        p.setMessageProperties(convertKeyValuePairs(messageProperties));
        p.setRoutingKeyCacheSize(routingKeyCacheSize);
        p.setExclusive(exclusive);
        p.setPublishingInterval(publishingInterval);
        p.setProducerRandomStartDelayInSeconds(producerRandomStartDelayInSeconds);
        p.setProducerSchedulerThreadCount(producerSchedulingThreads);
        p.setConsumersThreadPools(consumersThreadPools);
        p.setShutdownTimeout(shutdownTimeout);
        p.setServersStartUpTimeout(serversStartUpTimeout);
        p.setServersUpLimit(serversUpLimit);
        p.setPublishingRates(variableRates);
        p.setMessageSizes(variableSizes);
        p.setPolling(polling);
        p.setPollingInterval(pollingInterval);
        p.setNack(nack);
        p.setRequeue(requeue);
        p.setJsonBody(jsonBody);
        p.setBodyFieldCount(bodyFieldCount);
        p.setBodyCount(bodyCount);
        p.setConsumerArguments(consumerArguments);
        p.setQueuesInSequence(queueFile != null);
        p.setExitWhen(exitWhen);
        p.setCluster(uris.size() > 0);
        p.setConsumerStartDelay(consumerStartDelay);
        return p;
    }

    static String stopLine(Map<String, Integer> reasons) {
        StringBuilder stoppedLine = new StringBuilder("test stopped");
        if (reasons.size() > 0) {
            stoppedLine.append(" (");
            int count = 1;
            for (Map.Entry<String, Integer> reasonToCount : reasons.entrySet()) {
                stoppedLine.append(reasonToCount.getKey());
                if (reasonToCount.getValue() > 1) {
                    stoppedLine.append(" [").append(reasonToCount.getValue()).append("]");
                }
                if (count < reasons.size()) {
                    stoppedLine.append(", ");
                }
                count++;
            }
            stoppedLine.append(")");
        }
        return stoppedLine.toString();
    }

    private static PrintWriter openCsvFileForWriting(String outputFile, ShutdownService shutdownService) throws IOException {
        PrintWriter output;
        File file = new File(outputFile);
        if (file.exists()) {
            boolean deleted = file.delete();
            if (!deleted) {
                LOGGER.warn("Could not delete existing CSV file, will try to append at the end of the file");
            }
        }
        output = new PrintWriter(new BufferedWriter(new FileWriter(file, true)), true); //NOSONAR
        shutdownService.wrap(() -> output.close());
        return output;
    }

    private static ConnectionFactory configureNioIfRequested(CommandLineProxy cmd, ConnectionFactory factory) {
        int nbThreads = intArg(cmd, "niot", -1);
        int executorSize = intArg(cmd, "niotp", -1);
        if (nbThreads > 0 || executorSize > 0) {
            NioParams nioParams = new NioParams();
            int[] nbThreadsAndExecutorSize = getNioNbThreadsAndExecutorSize(nbThreads, executorSize);
            nioParams.setNbIoThreads(nbThreadsAndExecutorSize[0]);
            // FIXME we cannot limit the max size of the thread pool because of
            // the way NIO and automatic connection recovery work together.
            // If set, the thread pool is also used to dispatch connection closing,
            // where connection recovery actually occurs. In case of massive disconnecting,
            // the thread pool is busy closing the connections, connection recovery
            // kicks in the same used threads, and new connection cannot be opened as
            // there are no available threads anymore in the pool for NIO!
            nioParams.setNioExecutor(new ThreadPoolExecutor(
                nbThreadsAndExecutorSize[0], Integer.MAX_VALUE,
                30L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new NamedThreadFactory("perf-test-nio-")
            ));
            factory.useNio();
            factory.setNioParams(nioParams);
        }
        return factory;
    }

    protected static int [] getNioNbThreadsAndExecutorSize(int requestedNbThreads, int requestedExecutorSize) {
        // executor size must be slightly bigger than nb threads, see NioParams#setNioExecutor
        int extraThreadsForExecutor = 2;
        int nbThreadsToUse = requestedNbThreads > 0 ? requestedNbThreads : requestedExecutorSize - extraThreadsForExecutor;
        int executorSizeToUse = requestedExecutorSize > 0 ? requestedExecutorSize : Integer.MAX_VALUE;
        if (nbThreadsToUse <= 0 || executorSizeToUse <= 0) {
            throw new IllegalArgumentException(
                format("NIO number of threads and executor must be greater than 0: %d, %d", nbThreadsToUse, executorSizeToUse)
            );
        }
        return new int[] {
            nbThreadsToUse,
            executorSizeToUse > nbThreadsToUse ? executorSizeToUse : nbThreadsToUse + extraThreadsForExecutor
        };
    }

    static MulticastSet.CompletionHandler getCompletionHandler(MulticastParams p, ConcurrentMap<String, Integer> reasons) {
        MulticastSet.CompletionHandler completionHandler;
        if (p.hasLimit()) {
            int countLimit = 0;
            // producers and consumers will notify the completion
            // handler when they reach their message count
            if (p.getProducerMsgCount() > 0) {
                countLimit += p.getProducerThreadCount();
            }
            if (p.getConsumerMsgCount() > 0 || p.getExitWhen() == EXIT_WHEN.EMPTY
                || p.getExitWhen() == EXIT_WHEN.IDLE) {
                countLimit += p.getConsumerThreadCount();
            }
            LOGGER.debug("Creating completion handler with time limit {} and count limit {}",
                p.getTimeLimit(), countLimit
                );
            completionHandler = new MulticastSet.DefaultCompletionHandler(
                p.getTimeLimit(),
                countLimit,
                reasons);
        } else {
            completionHandler = new MulticastSet.NoLimitCompletionHandler(reasons);
        }
        return completionHandler;
    }

    public static void main(String[] args) throws IOException {
        Log.configureLog();
        PerfTestOptions perfTestOptions = new PerfTestOptions();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> perfTestOptions.shutdownService.close()));
        main(args, perfTestOptions
                .setSystemExiter(new JvmSystemExiter())
                .setSkipSslContextConfiguration(false)
                .setExceptionHandler(new RelaxedExceptionHandler())
                );
    }

    private static SSLContext getSslContextIfNecessary(CommandLineProxy cmd, Properties systemProperties) throws NoSuchAlgorithmException {
        SSLContext sslContext = null;
        if (hasOption(cmd, "udsc") || hasOption(cmd,"useDefaultSslContext")) {
            LOGGER.info("Using default SSL context as per command line option");
            sslContext = SSLContext.getDefault();
        }
        for (String propertyName : systemProperties.stringPropertyNames()) {
            if (propertyName != null && isPropertyTlsRelated(propertyName)) {
                LOGGER.info("TLS related system properties detected, using default SSL context");
                sslContext = SSLContext.getDefault();
                break;
            }
        }
        return sslContext;
    }

    private static boolean isPropertyTlsRelated(String propertyName) {
        return propertyName.startsWith("javax.net.ssl") || propertyName.startsWith("jdk.tls");
    }

    private static void usage(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("<program>", options);
    }

    private static void usageWithEnvironmentVariables(Options options) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setOptPrefix("");
        Options envOptions = new Options();
        forEach(options, option -> {
            if ("?".equals(option.getOpt()) || "v".equals(option.getOpt()) || "env".equals(option.getOpt())) {
                // no op
            } else {
                envOptions.addOption(LONG_OPTION_TO_ENVIRONMENT_VARIABLE.apply(option.getLongOpt()), false, option.getDescription());
            }

        });
        formatter.printHelp("<program>. For multi-value options, separate values " +
                "with commas, e.g. VARIABLE_RATE='100:60,1000:10,500:15'", envOptions);
    }

    static CommandLineParser getParser() {
        return new DefaultParser();
    }

    private static String lookUpInstanceSyncNamespace(CommandLineProxy cmd) {
        String instanceSyncNamespace = strArg(cmd, "isn", null);
        if (instanceSyncNamespace == null) {
            instanceSyncNamespace = System.getenv("MY_POD_NAMESPACE");
        }
        return instanceSyncNamespace;
    }

    static Options getOptions() {
        Options options = new Options();
        options.addOption(new Option("?", "help",                   false,"show usage"));
        options.addOption(new Option("d", "id",                     true, "test ID"));
        options.addOption(new Option("h", "uri",                    true, "connection URI"));
        options.addOption(new Option("H", "uris",                   true, "connection URIs (separated by commas)"));
        options.addOption(new Option("t", "type",                   true, "exchange type"));

        final Option exchangeOpt = new Option("e", "exchange name");
        exchangeOpt.setLongOpt("exchange");
        exchangeOpt.setOptionalArg(true);
        exchangeOpt.setArgs(1);
        options.addOption(exchangeOpt);

        options.addOption(new Option("u", "queue",                  true, "queue name"));
        options.addOption(new Option("k", "routing-key",            true, "routing key"));
        options.addOption(new Option("K", "random-routing-key",     false,"use random routing key per message"));
        options.addOption(new Option("sb", "skip-binding-queues",   false,"don't bind queues to the exchange"));
        options.addOption(new Option("i", "interval",               true, "sampling interval in seconds"));
        options.addOption(new Option("r", "rate",                   true, "producer rate limit"));
        options.addOption(new Option("R", "consumer-rate",          true, "consumer rate limit"));
        options.addOption(new Option("x", "producers",              true, "producer count"));
        options.addOption(new Option("y", "consumers",              true, "consumer count"));
        options.addOption(new Option("S", "slow-start",             false,"start consumers slowly (1 sec delay between each)"));
        options.addOption(new Option("X", "producer-channel-count", true, "channels per producer"));
        options.addOption(new Option("Y", "consumer-channel-count", true, "channels per consumer"));
        options.addOption(new Option("m", "ptxsize",                true, "producer tx size"));
        options.addOption(new Option("n", "ctxsize",                true, "consumer tx size"));
        options.addOption(new Option("c", "confirm",                true, "max unconfirmed publishes"));
        options.addOption(new Option("ct", "confirm-timeout",       true, "waiting timeout for unconfirmed publishes before failing (in seconds)"));
        options.addOption(new Option("a", "autoack",                false,"auto ack"));
        options.addOption(new Option("A", "multi-ack-every",        true, "multi ack every"));
        options.addOption(new Option("q", "qos",                    true, "consumer prefetch count"));
        options.addOption(new Option("Q", "global-qos",             true, "channel prefetch count"));
        options.addOption(new Option("s", "size",                   true, "message size in bytes"));
        options.addOption(new Option("z", "time",                   true, "run duration in seconds (unlimited by default)"));
        options.addOption(new Option("C", "pmessages",              true, "producer message count"));
        options.addOption(new Option("D", "cmessages",              true, "consumer message count"));
        Option flag =     new Option("f", "flag",                   true, "message flag(s), supported values: " +
                                                                                                      "persistent and mandatory. Use the option several times " +
                                                                                                      "to specify several values.");
        flag.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(flag);
        options.addOption(new Option("M", "framemax",               true, "frame max"));
        options.addOption(new Option("b", "heartbeat",              true, "heartbeat interval"));
        options.addOption(new Option("p", "predeclared",            false,"allow use of predeclared objects"));
        options.addOption(new Option("B", "body",                   true, "comma-separated list of files to use in message bodies"));
        options.addOption(new Option("T", "body-content-type",      true, "body content-type"));
        options.addOption(new Option("o", "output-file",            true, "output file for timing results"));
        options.addOption(new Option("ad", "auto-delete",           true, "should the queue be auto-deleted, default is true"));
        options.addOption(new Option("ms", "use-millis",            false,"should latency be collected in milliseconds, default is false. "
                                                                                                    + "Set to true if producers and consumers run on different machines."));
        Option queueArgumentsOption = new Option("qa", "queue-args",            true, "queue arguments as key/value pairs, separated by commas, "
            + "e.g. x-max-length=10");
        queueArgumentsOption.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(queueArgumentsOption);
        options.addOption(new Option("L", "consumer-latency",       true, "consumer latency in microseconds"));

        options.addOption(new Option("udsc", "use-default-ssl-context", false, "use JVM default SSL context"));
        options.addOption(new Option("se", "sasl-external", false, "use SASL EXTERNAL authentication, default is false. " +
                                                                   "Set to true if using client certificate authentication with the rabbitmq_auth_mechanism_ssl plugin."));

        options.addOption(new Option("v", "version",                false,"print version information"));

        options.addOption(new Option("qp", "queue-pattern",         true, "queue name pattern for creating queues in sequence"));
        options.addOption(new Option("qpf", "queue-pattern-from",     true, "queue name pattern range start (inclusive)"));
        options.addOption(new Option("qpt", "queue-pattern-to",       true, "queue name pattern range end (inclusive)"));
        options.addOption(new Option("hst", "heartbeat-sender-threads",       true, "number of threads for producers and consumers heartbeat senders"));
        options.addOption(new Option("mp", "message-properties",    true, "message properties as key/value pairs, separated by commas, "
                                                                                                    + "e.g. priority=5"));
        options.addOption(new Option("rkcs", "routing-key-cache-size",true, "size of the random routing keys cache. See --random-routing-key."));
        options.addOption(new Option("E", "exclusive",                false, "use server-named exclusive queues. "
                                                                                        + "Such queues can only be used by their declaring connection!"));
        options.addOption(new Option("P", "publishing-interval",true, "publishing interval in seconds (opposite of producer rate limit)"));
        options.addOption(new Option("prsd", "producer-random-start-delay",true, "max random delay in seconds to start producers"));
        options.addOption(new Option("pst", "producer-scheduler-threads",true, "number of threads to use when using --publishing-interval"));
        options.addOption(new Option("niot", "nio-threads",true, "number of NIO threads to use"));
        options.addOption(new Option("niotp", "nio-thread-pool",true, "size of NIO thread pool, should be slightly higher than number of NIO threads"));

        options.addOption(new Option("mh", "metrics-help",false, "show metrics usage"));

        options.addOption(new Option("env", "environment-variables",false, "show usage with environment variables"));

        options.addOption(new Option("dcr", "disable-connection-recovery",            false,"disable automatic connection recovery"));

        options.addOption(new Option("ctp", "consumers-thread-pools",true, "number of thread pools to use for all consumers, "
            + "default is to use a thread pool for each consumer"));

        options.addOption(new Option("st", "shutdown-timeout",true, "shutdown timeout, default is 5 seconds"));

        options.addOption(new Option("sst", "servers-startup-timeout",true,
                "start timeout in seconds (in case the servers(s) is (are) not available when the run starts). " +
                          "Default is to fail immediately if the servers(s) is (are) not available."));
        options.addOption(new Option("sul", "servers-up-limit",true,
                "number of available servers needed before starting the run. Used " +
                          "in conjunction with --servers-start-timeout. Default is deduced from --uri or --uris."));

        Option variableRate = new Option("vr", "variable-rate",true,
                "variable publishing rate with [RATE]:[DURATION] syntax, " +
                          "where [RATE] integer >= 0 and [DURATION] integer > 0. Use the option several times " +
                          "to specify several values.");
        variableRate.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(variableRate);

        Option variableSize = new Option("vs", "variable-size",true,
                "variable message size with [SIZE]:[DURATION] syntax, " +
                          "where [SIZE] integer > 0 and [DURATION] integer > 0. Use the option several times " +
                          "to specify several values.");
        variableSize.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(variableSize);

        Option variableConsumerLatency = new Option("vl", "variable-latency",true,
                "variable consumer processing latency with [MICROSECONDS]:[DURATION] syntax, " +
                        "where [MICROSECONDS] integer >= 0 and [DURATION] integer > 0. Use the option several times " +
                        "to specify several values.");
        variableConsumerLatency.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(variableConsumerLatency);

        options.addOption(new Option("po", "polling",false,
                "use basic.get to consume messages. " +
                "Do not use this in real applications."));
        options.addOption(new Option("pi", "polling-interval",true, "time to wait before polling with basic.get, " +
                "in millisecond, default is 0."));

        options.addOption(new Option("na", "nack", false, "nack messages, requeue them by default."));
        options.addOption(new Option("re", "requeue", true, "should nacked messages be requeued, default is true."));

        options.addOption(new Option("jb", "json-body", false, "generate a random JSON document for message body. " +
                "Use with --size."));
        options.addOption(new Option("bfc", "body-field-count", true, "number of pre-generated fields and values for body. " +
                "Use with --json-body. Default is 1000."));
        options.addOption(new Option("bc", "body-count", true, "number of pre-generated message bodies. " +
                "Use with --json-body. Default is 100."));
        options.addOption(new Option("ca", "consumer-args", true, "consumer arguments as key/value pairs, separated by commas, "
                + "e.g. x-priority=10"));
        options.addOption(new Option("cri", "connection-recovery-interval", true, "connection recovery interval in seconds. Default is 5 seconds. "
                + "Interval syntax, e.g. 30-60, is supported to specify an random interval between 2 values between each attempt."));

        options.addOption(new Option("qf", "queue-file", true, "file to look up queue names from"));
        options.addOption(new Option("sni", "server-name-indication", true, "server names for Server Name Indication TLS parameter, separated by commas"));
        options.addOption(new Option("qq", "quorum-queue", false,"create quorum queue(s)"));
        options.addOption(new Option("sq", "stream-queue", false,"create stream queue(s)"));
        options.addOption(new Option("ew", "exit-when", true, "exit when queue(s) empty or consumer(s) idle for 1 second, valid values are empty or idle"));
        options.addOption(new Option("csd", "consumer-start-delay", true, "fixed delay before starting consumers in seconds"));
        options.addOption(new Option("em", "exposed-metrics", true, "metrics to be exposed as key/value pairs, separated by commas, "
            + "e.g. expected_published=50000"));
        options.addOption(new Option("mf", "metrics-format", true, "metrics format to use on the console, possible values are "
            + MetricsFormatterFactory.types().stream().collect(Collectors.joining(", "))));

        options.addOption(new Option("mlb", "max-length-bytes", true, "max size of created queues, use 0 for no limit"));
        options.addOption(new Option("smssb", "stream-max-segment-size-bytes", true, "max size of stream segments when streams are in use"));
        options.addOption(new Option("ll", "leader-locator", true, "leader locator strategy for created quorum queues and streams. "
                    + "Possible values: client-local, balanced."));
        options.addOption(new Option("ma", "max-age", true,
            "max age of stream segments using the ISO 8601 duration format, "
            + "e.g. PT10M30S for 10 minutes 30 seconds, P5DT8H for 5 days 8 hours."));
        options.addOption(new Option("sco", "stream-consumer-offset", true,
            "stream offset to start listening from. "
            + "Valid values are 'first', 'last', 'next', an unsigned long, "
            + "or an ISO 8601 formatted timestamp (eg. 2022-06-03T07:45:54Z)."));
        options.addOption(new Option("ei", "expected-instances", true, "number of expected PerfTest instances "
            + "to synchronize. Default is 0, that is no synchronization."
            + "Test ID is mandatory when instance synchronization is in use."));
        options.addOption(new Option("isn", "instance-sync-namespace", true, "Kubernetes namespace for "
            + "instance synchronization"));
        options.addOption(new Option("ist", "instance-sync-timeout", true, "Instance synchronization time "
            + "in seconds. Default is 600 seconds."));
        return options;
    }

    static int intArg(CommandLineProxy cmd, char opt, int def) {
        return Integer.parseInt(cmd.getOptionValue(opt, Integer.toString(def)));
    }

    static int intArg(CommandLineProxy cmd, String opt, int def) {
        return Integer.parseInt(cmd.getOptionValue(opt, Integer.toString(def)));
    }

    static float floatArg(CommandLineProxy cmd, char opt, float def) {
        return Float.parseFloat(cmd.getOptionValue(opt, Float.toString(def)));
    }

    static boolean boolArg(CommandLineProxy cmd, String opt, boolean def) {
        return Boolean.parseBoolean(cmd.getOptionValue(opt, Boolean.toString(def)));
    }

    static List<String> lstArg(CommandLineProxy cmd, char opt) {
        return lstArg(cmd, String.valueOf(opt));
    }

    static List<String> lstArg(CommandLineProxy cmd, String opt) {
        String[] vals = cmd.getOptionValues(opt);
        if (vals == null) {
            vals = new String[] {};
        }
        return asList(vals);
    }

    static boolean hasOption(CommandLineProxy cmd, String opt) {
        return cmd.hasOption(opt);
    }

    static Map<String, Object> convertKeyValuePairs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return new LinkedHashMap<>();
        } else {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (String arg : args) {
                Map<String, Object> intermediaryArgs = convertKeyValuePairs(arg);
                if (intermediaryArgs != null) {
                    result.putAll(intermediaryArgs);
                }
            }
            return result;
        }
    }

    static Map<String, Object> convertKeyValuePairs(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return null;
        }
        return Arrays.stream(arg.split(",")).map(entry -> {
            String [] keyValue = entry.split("=");
            if (keyValue.length == 1) {
                return new Object[] {keyValue[0], ""};
            } else if ("true".equals(keyValue[1])) {
                return new Object[] {keyValue[0], true};
            } else if ("false".equals(keyValue[1])) {
                return new Object[] {keyValue[0], false};
            } else {
                try {
                    return new Object[] {keyValue[0], Long.parseLong(keyValue[1])};
                } catch(NumberFormatException e) {
                    return new Object[] {keyValue[0], keyValue[1]};
                }
            }
        }).map(keyValue -> {
            if ("x-dead-letter-exchange".equals(keyValue[0]) && "amq.default".equals(keyValue[1])) {
                return new String[] {"x-dead-letter-exchange", ""};
            }
            else if ("x-single-active-consumer".equals(keyValue[0])) {
                return new Object[] {"x-single-active-consumer", Boolean.parseBoolean(String.valueOf(keyValue[1]))};
            } else {
                return keyValue;
            }
        }).collect(Collectors.toMap(entry -> entry[0].toString(), entry -> entry[1],
                (o1, o2) -> o2, LinkedHashMap::new));
    }

    private static String getExchangeName(CommandLineProxy cmd, String def) {
        String exchangeName = strArg(cmd, 'e', null);
        if (exchangeName != null) {
            if (exchangeName == null || exchangeName.equals("amq.default")) {
                exchangeName = "";
            }
        } else {
            exchangeName = def;
        }
        return exchangeName;
    }

    private static void versionInformation() {
        String version = format(
            "RabbitMQ Perf Test %s (%s; %s)",
            Version.VERSION, Version.BUILD, Version.BUILD_TIMESTAMP
        );
        StringBuilder info = new StringBuilder();
        info.append(format("RabbitMQ AMQP Client version: %s%n", ClientVersion.VERSION));
        info.append(format("Java version: %s, vendor: %s%n",
            System.getProperty("java.version"), System.getProperty("java.vendor")));
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
           info.append(format("Java home: %s%n", javaHome));
        }
        info.append(format("Default locale: %s, platform encoding: %s%n",
            Locale.getDefault().toString(), Charset.defaultCharset()));
        info.append(format("OS name: %s, version: %s, arch: %s",
            System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch")));
        System.out.println("\u001B[1m" + version);
        System.out.println("\u001B[0m" + info);
    }

    static Duration parsePublishingInterval(String input) {
        BigDecimal decimalValue;
        try {
         decimalValue = new BigDecimal(input);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Must be a number");
        }
        if (decimalValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Must be positive");
        }
        Duration result = Duration.ofMillis(decimalValue.multiply(BigDecimal.valueOf(1000)).longValue());
        if (result.toMillis() < 100) {
            throw new IllegalArgumentException("Cannot be less than 0.1");
        }
        return result;
    }

    /**
     * Abstraction to ease testing or PerfTest usage as a library.
     */
    public static class PerfTestOptions {

        private SystemExiter systemExiter = new JvmSystemExiter();

        private boolean skipSslContextConfiguration = false;

        private ShutdownService shutdownService = new ShutdownService();

        private ExceptionHandler exceptionHandler = new DefaultExceptionHandler();

        private PrintStream consoleOut = System.out;

        private PrintStream consoleErr = System.err;

        private Function<String, String> argumentLookup = LONG_OPTION_TO_ENVIRONMENT_VARIABLE
            .andThen(ENVIRONMENT_VARIABLE_PREFIX)
            .andThen(ENVIRONMENT_VARIABLE_LOOKUP);

        public PerfTestOptions setSystemExiter(SystemExiter systemExiter) {
            this.systemExiter = systemExiter;
            return this;
        }

        public PerfTestOptions setSkipSslContextConfiguration(boolean skipSslContextConfiguration) {
            this.skipSslContextConfiguration = skipSslContextConfiguration;
            return this;
        }

        public PerfTestOptions setArgumentLookup(Function<String, String> argumentLookup) {
            this.argumentLookup = argumentLookup;
            return this;
        }

        public PerfTestOptions setShutdownService(ShutdownService shutdownService) {
            this.shutdownService = shutdownService;
            return this;
        }

        public PerfTestOptions setExceptionHandler(ExceptionHandler exceptionHandler) {
            this.exceptionHandler = exceptionHandler;
            return this;
        }

        public PerfTestOptions setConsoleOut(PrintStream consoleOut) {
            this.consoleOut = consoleOut;
            return this;
        }

        public PerfTestOptions setConsoleErr(PrintStream consoleErr) {
            this.consoleErr = consoleErr;
            return this;
        }

    }

    /**
     * Interface for exiting the JVM.
     * This abstraction is useful for testing and for PerfTest usage a library.
     */
    public interface SystemExiter {

        /**
         * Terminate the currently running Java Virtual Machine.
         * @param status
         */
        void exit(int status);

    }

    private static class JvmSystemExiter implements SystemExiter {

        @Override
        public void exit(int status) {
            System.exit(status);
        }

    }

    public static final Function<String, String> LONG_OPTION_TO_ENVIRONMENT_VARIABLE = option ->
        option.replace('-', '_').toUpperCase(Locale.ENGLISH);

    public static final Function<String, String> ENVIRONMENT_VARIABLE_PREFIX = name -> {
        String prefix = System.getenv("RABBITMQ_PERF_TEST_ENV_PREFIX");
        if (prefix == null || prefix.trim().isEmpty()) {
            return name;
        }
        if (prefix.endsWith("_")) {
            return prefix + name;
        } else {
            return prefix + "_" + name;
        }
    };

    static final Function<String, String> ENVIRONMENT_VARIABLE_LOOKUP = name -> System.getenv(name);

    enum EXIT_WHEN {
        NEVER, EMPTY, IDLE
    }

    private static ByteCapacity validateByteCapacity(String value, SystemExiter exiter, PrintStream output) {
        try {
           return ByteCapacity.from(value);
        } catch (IllegalArgumentException e) {
           validate(() -> Boolean.FALSE,
               "'" + value + "' is not a valid byte capacity, valid example values: 100gb, 50mb",
               exiter, output);
           return null;
        }
    }

    private static Duration validateDuration(String value, SystemExiter exiter, PrintStream output) {
        try {
            Duration duration = Duration.parse(value);
            if (duration.isNegative() || duration.isZero()) {
                validate(() -> Boolean.FALSE,
                    "'" + value + "' is not a valid duration, it must be positive",
                    exiter, output);
            }
            return duration;
        } catch (DateTimeParseException e) {
            validate(() -> Boolean.FALSE,
                "'" + value + "' is not a valid duration, valid example values: PT15M, PT10H",
                exiter, output);
            return null;
        }
    }

    private static Object validateStreamConsumerOffset(String value, SystemExiter exiter, PrintStream output) {
        // literal specification
        if ("first".equalsIgnoreCase(value) || "last".equalsIgnoreCase(value)
            || "next".equalsIgnoreCase(value)) {
            return value.toLowerCase();
        }
        // offset (unsigned long)
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            // OK, continue to try
        }
        try {
            TemporalAccessor accessor = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(value);
            return new Date(Instant.from(accessor).toEpochMilli());
        } catch (DateTimeParseException e) {
            // OK
        }
        validate(() -> Boolean.FALSE, "'" + value + "' is not a valid stream offset value."
            + "" , exiter, output);
        return null;
    }

    private static void validate(BooleanSupplier condition, String message, SystemExiter exiter,
        PrintStream output) {
        if (condition.getAsBoolean() == false) {
            output.println(message);
            exiter.exit(1);
        }
    }
}
