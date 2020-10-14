// Copyright (c) 2019-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import java.util.Arrays;
import java.util.List;

/**
 * {@link ValueIndicator} implementation with a constant value.
 *
 * @since 2.8.0
 */
class FixedValueIndicator<T> implements ValueIndicator<T> {

    private final T value;

    public FixedValueIndicator(T value) {
        this.value = value;
    }

    @Override
    public T getValue() {
        return value;
    }

    @Override
    public void start() {

    }

    @Override
    public boolean isVariable() {
        return false;
    }

    @Override
    public List<T> values() {
        return Arrays.asList(value);
    }
}
