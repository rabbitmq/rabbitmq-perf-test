// Copyright (c) 2018-Present Pivotal Software, Inc.  All rights reserved.
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

package com.rabbitmq.perf.it;

import com.rabbitmq.client.impl.NetworkConnection;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class Host {

    public static String capture(InputStream is)
        throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        StringBuilder buff = new StringBuilder();
        while ((line = br.readLine()) != null) {
            buff.append(line).append("\n");
        }
        return buff.toString();
    }

    public static Process executeCommand(String command) throws IOException {
        Process pr = executeCommandProcess(command);

        int ev = waitForExitValue(pr);
        if (ev != 0) {
            String stdout = capture(pr.getInputStream());
            String stderr = capture(pr.getErrorStream());
            throw new IOException("unexpected command exit value: " + ev +
                "\ncommand: " + command + "\n" +
                "\nstdout:\n" + stdout +
                "\nstderr:\n" + stderr + "\n");
        }
        return pr;
    }

    private static int waitForExitValue(Process pr) {
        while (true) {
            try {
                pr.waitFor();
                break;
            } catch (InterruptedException ignored) {
            }
        }
        return pr.exitValue();
    }

    private static Process executeCommandProcess(String command) throws IOException {
        String[] finalCommand;
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            finalCommand = new String[4];
            finalCommand[0] = "C:\\winnt\\system32\\cmd.exe";
            finalCommand[1] = "/y";
            finalCommand[2] = "/c";
            finalCommand[3] = command;
        } else {
            finalCommand = new String[3];
            finalCommand[0] = "/bin/sh";
            finalCommand[1] = "-c";
            finalCommand[2] = command;
        }
        return Runtime.getRuntime().exec(finalCommand);
    }

    public static Process rabbitmqctl(String command) throws IOException {
        return executeCommand(rabbitmqctlCommand() +
            " " + command);
    }

    public static void stopBrokerApp() throws IOException {
        rabbitmqctl("stop_app");
    }

    public static void startBrokerApp() throws IOException {
        rabbitmqctl("start_app");
    }

    public static String rabbitmqctlCommand() {
        return System.getProperty("rabbitmqctl.bin");
    }

    public static void closeConnection(String pid) throws IOException {
        rabbitmqctl("close_connection '" + pid + "' 'Closed via rabbitmqctl'");
    }

    public static void closeAllConnections(List<ConnectionInfo> connectionInfos) throws IOException {
        for (ConnectionInfo connectionInfo : connectionInfos) {
            closeConnection(connectionInfo.getPid());
        }
    }

    public static void closeAllConnections() throws IOException {
        closeAllConnections(listConnections());
    }

    public static List<ConnectionInfo> listConnections() throws IOException {
        String output = capture(rabbitmqctl("list_connections -q pid peer_port").getInputStream());
        String[] allLines = output.split("\n");

        ArrayList<ConnectionInfo> result = new ArrayList<ConnectionInfo>();
        for (String line : allLines) {
            // line: <rabbit@mercurio.1.11491.0>	58713
            String[] columns = line.split("\t");
            result.add(new ConnectionInfo(columns[0], Integer.valueOf(columns[1])));
        }
        return result;
    }

    private static Host.ConnectionInfo findConnectionInfoFor(List<ConnectionInfo> xs, NetworkConnection c) {
        Host.ConnectionInfo result = null;
        for (Host.ConnectionInfo ci : xs) {
            if (c.getLocalPort() == ci.getPeerPort()) {
                result = ci;
                break;
            }
        }
        return result;
    }

    public static class ConnectionInfo {

        private final String pid;
        private final int peerPort;

        public ConnectionInfo(String pid, int peerPort) {
            this.pid = pid;
            this.peerPort = peerPort;
        }

        public String getPid() {
            return pid;
        }

        public int getPeerPort() {
            return peerPort;
        }
    }
}
