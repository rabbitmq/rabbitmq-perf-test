// Copyright (c) 2007-Present Pivotal Software, Inc.  All rights reserved.
//
// This software, the RabbitMQ Java client library, is triple-licensed under the
// Mozilla Public License 1.1 ("MPL"), the GNU General Public License version 2
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

import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.rabbitmq.client.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;

import static java.util.Arrays.asList;

public class PerfTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerfTest.class);

    public static void main(String[] args) {
        Options options = getOptions();
        CommandLineParser parser = new GnuParser();
        try {
            CommandLine cmd = parser.parse(options, args);

            if (cmd.hasOption('?')) {
                usage(options);
                System.exit(0);
            }
            String testID = new SimpleDateFormat("HHmmss-SSS").format(Calendar.
                getInstance().getTime());
            testID                   = strArg(cmd, 'd', "test-"+testID);
            String exchangeType      = strArg(cmd, 't', "direct");
            String exchangeName      = getExchangeName(cmd, exchangeType);
            String queueNames        = strArg(cmd, 'u', "");
            String routingKey        = strArg(cmd, 'k', null);
            boolean randomRoutingKey = cmd.hasOption('K');
            int samplingInterval     = intArg(cmd, 'i', 1);
            float producerRateLimit  = floatArg(cmd, 'r', 0.0f);
            float consumerRateLimit  = floatArg(cmd, 'R', 0.0f);
            int producerCount        = intArg(cmd, 'x', 1);
            int consumerCount        = intArg(cmd, 'y', 1);
            int producerChannelCount = intArg(cmd, 'X', 1);
            int consumerChannelCount = intArg(cmd, 'Y', 1);
            int producerTxSize       = intArg(cmd, 'm', 0);
            int consumerTxSize       = intArg(cmd, 'n', 0);
            long confirm             = intArg(cmd, 'c', -1);
            boolean autoAck          = cmd.hasOption('a');
            int multiAckEvery        = intArg(cmd, 'A', 0);
            int channelPrefetch      = intArg(cmd, 'Q', 0);
            int consumerPrefetch     = intArg(cmd, 'q', 0);
            int minMsgSize           = intArg(cmd, 's', 0);
            int timeLimit            = intArg(cmd, 'z', 0);
            int producerMsgCount     = intArg(cmd, 'C', 0);
            int consumerMsgCount     = intArg(cmd, 'D', 0);
            List<?> flags            = lstArg(cmd, 'f');
            int frameMax             = intArg(cmd, 'M', 0);
            int heartbeat            = intArg(cmd, 'b', 0);
            String bodyFiles         = strArg(cmd, 'B', null);
            String bodyContentType   = strArg(cmd, 'T', null);
            boolean predeclared      = cmd.hasOption('p');
            boolean legacyMetrics    = cmd.hasOption('l');
            boolean autoDelete       = boolArg(cmd, "ad", true);
            String queueArgs         = strArg(cmd, "qa", null);
            int consumerLatencyInMicroseconds = intArg(cmd, 'L', 0);

            String uri               = strArg(cmd, 'h', "amqp://localhost");
            String urisParameter     = strArg(cmd, 'H', null);
            String outputFile        = strArg(cmd, 'o', null);

            final PrintWriter output;
            if (outputFile != null) {
                File file = new File(outputFile);
                if (file.exists()) {
                    file.delete();
                }
                output = new PrintWriter(new BufferedWriter(new FileWriter(file)), true);
                Runtime.getRuntime().addShutdownHook(new Thread() {

                    @Override
                    public void run() {
                        output.close();
                    }
                });
            } else {
                output = null;
            }

            List<String> uris = null;
            if(urisParameter != null) {
                String [] urisArray = urisParameter.split(",");
                for(int i = 0; i< urisArray.length; i++) {
                    urisArray[i] = urisArray[i].trim();
                }
                uris = asList(urisArray);
            } else {
                uris = Collections.singletonList(uri);
            }

            //setup
            PrintlnStats stats = new PrintlnStats(testID,
                1000L * samplingInterval,
                producerCount > 0,
                consumerCount > 0,
                (flags.contains("mandatory") ||
                    flags.contains("immediate")),
                confirm != -1, legacyMetrics, output);

            SSLContext sslContext = getSslContextIfNecessary(cmd, System.getProperties());

            ConnectionFactory factory = new ConnectionFactory();
            if (sslContext != null) {
                factory.useSslProtocol(sslContext);
            }
            factory.setShutdownTimeout(0); // So we still shut down even with slow consumers
            factory.setUri(uris.get(0));
            factory.setRequestedFrameMax(frameMax);
            factory.setRequestedHeartbeat(heartbeat);

            MulticastParams p = new MulticastParams();
            p.setAutoAck(               autoAck);
            p.setAutoDelete(            autoDelete);
            p.setConfirm(               confirm);
            p.setConsumerCount(         consumerCount);
            p.setConsumerChannelCount(  consumerChannelCount);
            p.setConsumerMsgCount(      consumerMsgCount);
            p.setConsumerRateLimit(     consumerRateLimit);
            p.setConsumerTxSize(        consumerTxSize);
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
            p.setQueueNames(            asList(queueNames.split(",")));
            p.setRoutingKey(            routingKey);
            p.setRandomRoutingKey(      randomRoutingKey);
            p.setProducerRateLimit(     producerRateLimit);
            p.setTimeLimit(             timeLimit);
            p.setBodyFiles(             bodyFiles == null ? null : asList(bodyFiles.split(",")));
            p.setBodyContentType(       bodyContentType);
            p.setQueueArguments(queueArguments(queueArgs));
            p.setConsumerLatencyInMicroseconds(consumerLatencyInMicroseconds);

            MulticastSet set = new MulticastSet(stats, factory, p, testID, uris);
            set.run(true);

            stats.printFinal();
        }
        catch( ParseException exp ) {
            System.err.println("Parsing failed. Reason: " + exp.getMessage());
            usage(options);
        } catch (Exception e) {
            System.err.println("Main thread caught exception: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static SSLContext getSslContextIfNecessary(CommandLine cmd, Properties systemProperties) throws NoSuchAlgorithmException {
        SSLContext sslContext = null;
        if (cmd.hasOption("useDefaultSslContext")) {
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

    private static Options getOptions() {
        Options options = new Options();
        options.addOption(new Option("?", "help",                   false,"show usage"));
        options.addOption(new Option("d", "id",                     true, "test ID"));
        options.addOption(new Option("h", "uri",                    true, "connection URI"));
        options.addOption(new Option("H", "uris",                   true, "connection URIs (separated by commas)"));
        options.addOption(new Option("t", "type",                   true, "exchange type"));

        final Option exchangeOpt = new Option("e", "exchange name");
        exchangeOpt.setLongOpt("exchange");
        exchangeOpt.setOptionalArg(true);
        options.addOption(exchangeOpt);

        options.addOption(new Option("u", "queue",                  true, "queue name"));
        options.addOption(new Option("k", "routingKey",             true, "routing key"));
        options.addOption(new Option("K", "randomRoutingKey",       false,"use random routing key per message"));
        options.addOption(new Option("i", "interval",               true, "sampling interval in seconds"));
        options.addOption(new Option("r", "rate",                   true, "producer rate limit"));
        options.addOption(new Option("R", "consumerRate",           true, "consumer rate limit"));
        options.addOption(new Option("x", "producers",              true, "producer count"));
        options.addOption(new Option("y", "consumers",              true, "consumer count"));
        options.addOption(new Option("X", "producerChannelCount",   true, "channels per producer"));
        options.addOption(new Option("Y", "consumerChannelCount",   true, "channels per consumer"));
        options.addOption(new Option("m", "ptxsize",                true, "producer tx size"));
        options.addOption(new Option("n", "ctxsize",                true, "consumer tx size"));
        options.addOption(new Option("c", "confirm",                true, "max unconfirmed publishes"));
        options.addOption(new Option("a", "autoack",                false,"auto ack"));
        options.addOption(new Option("A", "multiAckEvery",          true, "multi ack every"));
        options.addOption(new Option("q", "qos",                    true, "consumer prefetch count"));
        options.addOption(new Option("Q", "globalQos",              true, "channel prefetch count"));
        options.addOption(new Option("s", "size",                   true, "message size in bytes"));
        options.addOption(new Option("z", "time",                   true, "run duration in seconds (unlimited by default)"));
        options.addOption(new Option("C", "pmessages",              true, "producer message count"));
        options.addOption(new Option("D", "cmessages",              true, "consumer message count"));
        Option flag =     new Option("f", "flag",                   true, "message flag");
        flag.setArgs(Option.UNLIMITED_VALUES);
        options.addOption(flag);
        options.addOption(new Option("M", "framemax",               true, "frame max"));
        options.addOption(new Option("b", "heartbeat",              true, "heartbeat interval"));
        options.addOption(new Option("p", "predeclared",            false,"allow use of predeclared objects"));
        options.addOption(new Option("B", "body",                   true, "comma-separated list of files to use in message bodies"));
        options.addOption(new Option("T", "bodyContenType",         true, "body content-type"));
        options.addOption(new Option("l", "legacyMetrics",          false, "display legacy metrics (min/avg/max latency)"));
        options.addOption(new Option("o", "outputFile",             true, "output file for timing results"));
        options.addOption(new Option("ad", "autoDelete",            true, "should the queue be auto-deleted, default is true"));
        options.addOption(new Option("qa", "queueArgs",             true, "queue arguments as key/pair values, separated by commas"));
        options.addOption(new Option("L", "consumerLatency",        true, "consumer latency in microseconds"));
        options.addOption(new Option("useDefaultSslContext", "useDefaultSslContext", false,"use JVM default SSL context"));
        return options;
    }

    private static String strArg(CommandLine cmd, char opt, String def) {
        return cmd.getOptionValue(opt, def);
    }

    private static String strArg(CommandLine cmd, String opt, String def) {
        return cmd.getOptionValue(opt, def);
    }

    private static int intArg(CommandLine cmd, char opt, int def) {
        return Integer.parseInt(cmd.getOptionValue(opt, Integer.toString(def)));
    }

    private static float floatArg(CommandLine cmd, char opt, float def) {
        return Float.parseFloat(cmd.getOptionValue(opt, Float.toString(def)));
    }

    private static boolean boolArg(CommandLine cmd, String opt, boolean def) {
        return Boolean.parseBoolean(cmd.getOptionValue(opt, Boolean.toString(def)));
    }

    private static List<?> lstArg(CommandLine cmd, char opt) {
        String[] vals = cmd.getOptionValues('f');
        if (vals == null) {
            vals = new String[] {};
        }
        return asList(vals);
    }

    private static Map<String, Object> queueArguments(String arg) {
        if (arg == null || arg.trim().isEmpty()) {
            return null;
        }
        Map<String, Object> queueArguments = new HashMap<String, Object>();
        for (String entry : arg.split(",")) {
            String [] keyValue = entry.split("=");
            try {
                queueArguments.put(keyValue[0], Long.parseLong(keyValue[1]));
            } catch(NumberFormatException e) {
                queueArguments.put(keyValue[0], keyValue[1]);
            }
        }
        return queueArguments;
    }

    private static String getExchangeName(CommandLine cmd, String def) {
        String exchangeName = null;
        if (cmd.hasOption('e')) {
            exchangeName = cmd.getOptionValue('e');
            // TODO: what would be a good constant to use for the default exchange?
            if (exchangeName == null || exchangeName.equalsIgnoreCase("AMQP-default")) {
                exchangeName = "";
            }
        } else {
            exchangeName = def;
        }
        return exchangeName;
    }

    private static class PrintlnStats extends Stats {
        private final boolean sendStatsEnabled;
        private final boolean recvStatsEnabled;
        private final boolean returnStatsEnabled;
        private final boolean confirmStatsEnabled;
        private final boolean legacyMetrics;

        private final String testID;
        private final PrintWriter out;

        public PrintlnStats(String testID, long interval,
            boolean sendStatsEnabled, boolean recvStatsEnabled,
            boolean returnStatsEnabled, boolean confirmStatsEnabled,
            boolean legacyMetrics,
            PrintWriter out) {
            super(interval);
            this.sendStatsEnabled = sendStatsEnabled;
            this.recvStatsEnabled = recvStatsEnabled;
            this.returnStatsEnabled = returnStatsEnabled;
            this.confirmStatsEnabled = confirmStatsEnabled;
            this.testID = testID;
            this.legacyMetrics = legacyMetrics;
            this.out = out;
            if (out != null) {
                out.println("id,time (s),sent (msg/s),returned (msg/s),confirmed (msg/s), nacked (msg/s), received (msg/s),"
                    + "min latency (microseconds),median latency (microseconds),75th p. latency (microseconds),95th p. latency (microseconds),"
                    + "99th p. latency (microseconds)");
            }

        }

        @Override
        protected void report(long now) {
            String output = "id: " + testID + ", ";

            output += "time: " + String.format("%.3f", (now - startTime)/1000.0) + "s";
            output +=
                getRate("sent",      sendCountInterval,    sendStatsEnabled,                        elapsedInterval) +
                    getRate("returned",  returnCountInterval,  sendStatsEnabled && returnStatsEnabled,  elapsedInterval) +
                    getRate("confirmed", confirmCountInterval, sendStatsEnabled && confirmStatsEnabled, elapsedInterval) +
                    getRate("nacked",    nackCountInterval,    sendStatsEnabled && confirmStatsEnabled, elapsedInterval) +
                    getRate("received",  recvCountInterval,    recvStatsEnabled,                        elapsedInterval);

            if (legacyMetrics) {
                output += (latencyCountInterval > 0 ?
                    ", min/avg/max latency: " +
                        minLatency/1000L + "/" +
                        cumulativeLatencyInterval / (1000L * latencyCountInterval) + "/" +
                        maxLatency/1000L + " microseconds " :
                    "");
            } else {
                output += (latencyCountInterval > 0 ?
                    ", min/median/75th/95th/99th latency: "
                        + latency.getSnapshot().getMin()/1000L + "/"
                        + (long) latency.getSnapshot().getMedian()/1000L + "/"
                        + (long) latency.getSnapshot().get75thPercentile()/1000L + "/"
                        + (long) latency.getSnapshot().get95thPercentile()/1000L + "/"
                        + (long) latency.getSnapshot().get99thPercentile()/1000L +  " microseconds" :
                    "");
            }

            System.out.println(output);
            if (out != null) {
                out.println(testID + "," + String.format("%.3f", (now - startTime)/1000.0) + "," +
                    rate(sendCountInterval, elapsedInterval, sendStatsEnabled)+ "," +
                    rate(returnCountInterval, elapsedInterval, sendStatsEnabled && returnStatsEnabled)+ "," +
                    rate(confirmCountInterval, elapsedInterval, sendStatsEnabled && confirmStatsEnabled)+ "," +
                    rate(nackCountInterval, elapsedInterval, sendStatsEnabled && confirmStatsEnabled)+ "," +
                    rate(recvCountInterval, elapsedInterval, recvStatsEnabled) + "," +
                    (latencyCountInterval > 0 ?
                        latency.getSnapshot().getMin()/1000L + "," +
                        (long) latency.getSnapshot().getMedian()/1000L + "," +
                        (long) latency.getSnapshot().get75thPercentile()/1000L + "," +
                        (long) latency.getSnapshot().get95thPercentile()/1000L + "," +
                        (long) latency.getSnapshot().get99thPercentile()/1000L
                        : ",,,,")
                );
            }

        }

        private String getRate(String descr, long count, boolean display,
            long elapsed) {
            if (display)
                return ", " + descr + ": " + formatRate(1000.0 * count / elapsed) + " msg/s";
            else
                return "";
        }

        public void printFinal() {
            long now = System.currentTimeMillis();

            System.out.println("id: " + testID + ", sending rate avg: " +
                formatRate(sendCountTotal * 1000.0 / (now - startTime)) +
                " msg/s");

            long elapsed = now - startTime;
            if (elapsed > 0) {
                System.out.println("id: " + testID + ", receiving rate avg: " +
                    formatRate(recvCountTotal * 1000.0 / elapsed) +
                    " msg/s");
            }
        }

        private static String formatRate(double rate) {
            if (rate == 0.0)    return String.format("%d", (long)rate);
            else if (rate < 1)  return String.format("%1.2f", rate);
            else if (rate < 10) return String.format("%1.1f", rate);
            else                return String.format("%d", (long)rate);
        }

        private static String rate(long count, long elapsed, boolean display) {
            if (display) {
                return formatRate(1000.0 * count / elapsed);
            } else {
                return "";
            }
        }
    }
}
