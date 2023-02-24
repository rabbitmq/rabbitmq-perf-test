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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** API to easily configure byte capacities. */
class ByteCapacity implements Comparable<ByteCapacity> {

  private static final String UNIT_MB = "mb";
  private static final int KILOBYTES_MULTIPLIER = 1000;
  private static final int MEGABYTES_MULTIPLIER = 1000 * 1000;
  private static final int GIGABYTES_MULTIPLIER = 1000 * 1000 * 1000;
  private static final long TERABYTES_MULTIPLIER = 1000L * 1000L * 1000L * 1000L;

  private static final Pattern PATTERN =
      Pattern.compile("^(?<size>\\d*)((?<unit>kb|mb|gb|tb))?$", Pattern.CASE_INSENSITIVE);
  private static final String GROUP_SIZE = "size";
  private static final String GROUP_UNIT = "unit";
  private static final String UNIT_KB = "kb";
  private static final String UNIT_GB = "gb";
  private static final String UNIT_TB = "tb";

  private static final Map<String, BiFunction<Long, String, ByteCapacity>> CONSTRUCTORS =
      Collections.unmodifiableMap(
          new HashMap<String, BiFunction<Long, String, ByteCapacity>>() {
            {
              put(UNIT_KB, (size, input) -> ByteCapacity.kB(size, input));
              put(UNIT_MB, (size, input) -> ByteCapacity.MB(size, input));
              put(UNIT_GB, (size, input) -> ByteCapacity.GB(size, input));
              put(UNIT_TB, (size, input) -> ByteCapacity.TB(size, input));
            }
          });

  private final long bytes;
  private final String input;

  private ByteCapacity(long bytes) {
    this(bytes, String.valueOf(bytes));
  }

  private ByteCapacity(long bytes, String input) {
    this.bytes = bytes;
    this.input = input;
  }

  public static ByteCapacity B(long bytes) {
    return new ByteCapacity(bytes);
  }

  public static ByteCapacity kB(long kilobytes) {
    return new ByteCapacity(kilobytes * KILOBYTES_MULTIPLIER);
  }

  public static ByteCapacity MB(long megabytes) {
    return new ByteCapacity(megabytes * MEGABYTES_MULTIPLIER);
  }

  public static ByteCapacity GB(long gigabytes) {
    return new ByteCapacity(gigabytes * GIGABYTES_MULTIPLIER);
  }

  public static ByteCapacity TB(long terabytes) {
    return new ByteCapacity(terabytes * TERABYTES_MULTIPLIER);
  }

  private static ByteCapacity kB(long kilobytes, String input) {
    return new ByteCapacity(kilobytes * KILOBYTES_MULTIPLIER, input);
  }

  private static ByteCapacity MB(long megabytes, String input) {
    return new ByteCapacity(megabytes * MEGABYTES_MULTIPLIER, input);
  }

  private static ByteCapacity GB(long gigabytes, String input) {
    return new ByteCapacity(gigabytes * GIGABYTES_MULTIPLIER, input);
  }

  private static ByteCapacity TB(long terabytes, String input) {
    return new ByteCapacity(terabytes * TERABYTES_MULTIPLIER, input);
  }

  public static ByteCapacity from(String value) {
    Matcher matcher = PATTERN.matcher(value);
    if (matcher.matches()) {
      long size = Long.valueOf(matcher.group(GROUP_SIZE));
      String unit = matcher.group(GROUP_UNIT);
      ByteCapacity result;
      if (unit == null) {
        result = ByteCapacity.B(size);
      } else {
        return CONSTRUCTORS
            .getOrDefault(
                unit.toLowerCase(),
                (v, input) -> {
                  throw new IllegalArgumentException("Unknown capacity unit: " + unit);
                })
            .apply(size, value);
      }
      return result;
    } else {
      throw new IllegalArgumentException("Cannot parse value for byte capacity: " + value);
    }
  }

  public long toBytes() {
    return bytes;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ByteCapacity that = (ByteCapacity) o;
    return bytes == that.bytes;
  }

  @Override
  public int hashCode() {
    return Objects.hash(bytes);
  }

  @Override
  public String toString() {
    return this.input;
  }

  @Override
  public int compareTo(ByteCapacity other) {
    return Long.compare(this.bytes, other.bytes);
  }
}
