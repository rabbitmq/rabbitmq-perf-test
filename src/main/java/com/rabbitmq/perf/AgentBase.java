// Copyright (c) 2007-2022 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.impl.recovery.AutorecoveringConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketException;

/**
 *
 */
public abstract class AgentBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentBase.class);

    private volatile TopologyRecording topologyRecording;

    public void setTopologyRecording(TopologyRecording topologyRecording) {
        this.topologyRecording = topologyRecording;
    }

    protected TopologyRecording topologyRecording() {
        return this.topologyRecording;
    }

    protected boolean isConnectionRecoveryTriggered(ShutdownSignalException e) {
        return AutorecoveringConnection.DEFAULT_CONNECTION_RECOVERY_TRIGGERING_CONDITION.test(e);
    }

    protected void handleShutdownSignalExceptionOnWrite(Recovery.RecoveryProcess recoveryProcess, ShutdownSignalException e) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "Handling write error, recovery process enabled? {}, condition to trigger connection recovery? {}",
                recoveryProcess.isEnabled(), isConnectionRecoveryTriggered(e), e
            );
        }
        if (shouldStop(recoveryProcess, e)) {
            throw e;
        }
    }

    protected boolean shouldStop(Recovery.RecoveryProcess recoveryProcess, ShutdownSignalException e) {
        if (recoveryProcess.isEnabled()) {
            // we stop only if the error isn't likely to trigger connection recovery
            return !isConnectionRecoveryTriggered(e);
        } else {
            return true;
        }
    }

    protected void dealWithWriteOperation(WriteOperation writeOperation, Recovery.RecoveryProcess recoveryProcess) throws IOException {
        try {
            writeOperation.call();
        } catch (ShutdownSignalException e) {
            handleShutdownSignalExceptionOnWrite(recoveryProcess, e);
        } catch (SocketException e) {
            if (recoveryProcess.isEnabled()) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                        "Socket exception in write, recovery process is enabled, ignoring to let connection recovery carry on"
                    );
                }
            } else {
                throw e;
            }
        }
    }

    public abstract void recover(TopologyRecording topologyRecording);

    protected interface AgentState {

        long getLastStatsTime();

        int getMsgCount();

        int incrementMessageCount();
    }

    @FunctionalInterface
    interface WriteOperation {
        void call() throws IOException;
    }
}
