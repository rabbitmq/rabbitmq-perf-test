// Copyright (c) 2023 VMware, Inc. or its affiliates.  All rights reserved.
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

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;

interface FunctionalLogger {

  FunctionalLogger NO_OP =
      new FunctionalLogger() {
        @Override
        public void published(
            int producerId,
            long timestamp,
            long publishingId,
            AMQP.BasicProperties messageProperties,
            byte[] body) {}

        @Override
        public void receivedPublishConfirm(
            int producerId, boolean confirmed, long publishingId, int confirmCount) {}

        @Override
        public void publishConfirmed(
            int producerId, boolean confirmed, long publishingId, long timestamp) {}

        @Override
        public void received(
            int consumerId,
            long timestamp,
            Envelope envelope,
            AMQP.BasicProperties messageProperty,
            byte[] body) {}

        @Override
        public void acknowledged(
            int consumerId, long timestamp, Envelope envelope, int ackedCount) {}
      };

  void published(
      int producerId,
      long timestamp,
      long publishingId,
      AMQP.BasicProperties messageProperties,
      byte[] body);

  void receivedPublishConfirm(
      int producerId, boolean confirmed, long publishingId, int confirmCount);

  void publishConfirmed(int producerId, boolean confirmed, long publishingId, long timestamp);

  void received(
      int consumerId,
      long timestamp,
      Envelope envelope,
      AMQP.BasicProperties messageProperty,
      byte[] body);

  void acknowledged(int consumerId, long timestamp, Envelope envelope, int ackedCount);

  default boolean isNoOp() {
    return this == NO_OP;
  }
}
