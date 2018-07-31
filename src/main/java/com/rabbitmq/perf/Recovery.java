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

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */
public class Recovery {

    public static final RecoveryProcess NO_OP_RECOVERY_PROCESS = new RecoveryProcess() {

        @Override
        public void init(AgentBase agent) {
        }

        @Override
        public boolean isRecoverying() {
            return false;
        }

        @Override
        public boolean isEnabled() {
            return false;
        }
    };
    private static final Logger LOGGER = LoggerFactory.getLogger(Recovery.class);

    static Recovery.RecoveryProcess setupRecoveryProcess(Connection connection, TopologyRecording topologyRecording) {
        if (Utils.isRecoverable(connection)) {
            AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
            AtomicReference<AgentBase> agentReference = new AtomicReference<>();
            Recovery.RecoveryProcess recoveryProcess = new Recovery.RecoveryProcess() {

                @Override
                public void init(AgentBase agent) {
                    agentReference.set(agent);
                }

                @Override
                public boolean isRecoverying() {
                    return recoveryInProgress.get();
                }

                @Override
                public boolean isEnabled() {
                    return true;
                }
            };

            ((AutorecoveringConnection) connection).addRecoveryListener(new RecoveryListener() {

                @Override
                public void handleRecoveryStarted(Recoverable recoverable) {
                    recoveryInProgress.set(true);
                }

                @Override
                public void handleRecovery(Recoverable recoverable) {
                    LOGGER.debug("Starting topology recovery for connection {}", connection.getClientProvidedName());
                    topologyRecording.recover(connection);
                    LOGGER.debug("Topology recovery done for connection {}, starting agent recovery", connection.getClientProvidedName());
                    agentReference.get().recover(topologyRecording);
                    recoveryInProgress.set(false);
                    LOGGER.debug("Connection recovery done for connection {}", connection.getClientProvidedName());
                }
            });
            connection.addShutdownListener(cause -> {
                if (AgentBase.CONNECTION_RECOVERY_TRIGGERED.test(cause)) {
                    LOGGER.debug("Setting recovery in progress flag for connection {}", connection.getClientProvidedName());
                    recoveryInProgress.set(true);
                }
            });
            return recoveryProcess;
        } else {
            return Recovery.NO_OP_RECOVERY_PROCESS;
        }
    }

    interface RecoveryProcess {

        void init(AgentBase agent);

        boolean isRecoverying();

        boolean isEnabled();
    }
}
