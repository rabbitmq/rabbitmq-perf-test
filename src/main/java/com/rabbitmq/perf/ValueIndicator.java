// Copyright (c) 2019-2023 Broadcom. All Rights Reserved.
// The term "Broadcom" refers to Broadcom Inc. and/or its subsidiaries.
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

import java.util.List;

/**
 * Contract to give hints about the value of a variable.
 *
 * <p>Can be used to roll over a given set of values for messages rate or size.
 *
 * @since 2.8.0
 */
interface ValueIndicator<T> {

  /**
   * Returns the current value of the variable.
   *
   * @return
   */
  T getValue();

  /** Start method for internal initialization before requesting the value of the variable. */
  void start();

  /**
   * Indicates whether the value is supposed to change or not.
   *
   * @return
   */
  boolean isVariable();

  /**
   * Returns all the possible values for the variable.
   *
   * @return
   */
  List<T> values();

  /**
   * Register a listener to get notified when the hinted value changes.
   *
   * @param listener
   */
  default void register(Listener<T> listener) {}

  /**
   * Contract to get notified when the hinted value changes.
   *
   * @param <T>
   */
  interface Listener<T> {

    void valueChanged(T oldValue, T newValue);
  }
}
