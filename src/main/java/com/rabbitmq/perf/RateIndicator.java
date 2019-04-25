// Copyright (c) 2019 Pivotal Software, Inc.  All rights reserved.
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

/**
 * Contract to give hints about a rate to maintain.
 *
 * @since 2.8.0
 */
interface RateIndicator {

    /**
     * Returns the current requested rate.
     *
     * @return
     */
    float getRate();

    /**
     * Start method for internal initialization before requesting rate hints.
     */
    void start();

    /**
     * Indicates whether the rate is supposed to change or not.
     *
     * @return
     */
    boolean isVariable();

}
