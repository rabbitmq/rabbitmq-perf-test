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

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

class RandomJsonMessageBodySource implements MessageBodySource {

  public static final String DIGITS = "0123456789";
  private static final Charset CHARSET = Charset.forName("UTF-8");
  private static final long ZERO = 0L;
  private static final String CONTENT_TYPE = "application/json";
  private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
  public static final String LOWER = UPPER.toLowerCase(Locale.ROOT);
  public static final String ALPHANUM = UPPER + LOWER + DIGITS;
  private final byte[][] bodies;

  RandomJsonMessageBodySource(int bodySize, int fieldValueCount, int bodyCount) {
    bodies = new byte[bodyCount][];
    List<String> fields = new ArrayList<>(fieldValueCount);
    List<String> values = new ArrayList<>(fieldValueCount);

    Random random = new Random();
    int maxSize = 200;
    char[] buf = new char[maxSize];
    char[] fieldsSymbols = LOWER.toCharArray();
    char[] valuesSymbols = ALPHANUM.toCharArray();
    IntStream.range(0, fieldValueCount)
        .forEach(
            i -> {
              fields.add(random(random, buf, 30, fieldsSymbols));
              values.add(random(random, buf, maxSize, valuesSymbols));
            });

    List<String> shuffledFields = new ArrayList<>(fields);
    IntStream.range(0, bodyCount)
        .forEach(
            i -> {
              StringBuilder builder = new StringBuilder("{ ");
              Collections.shuffle(shuffledFields);
              Iterator<String> fieldsIterator = shuffledFields.iterator();
              while (true) {
                if (!fieldsIterator.hasNext()) {
                  fieldsIterator = shuffledFields.iterator();
                }
                builder.append("\"").append(fieldsIterator.next()).append("\" : ");
                builder.append("\"").append(values.get(random.nextInt(values.size()))).append("\"");
                if (builder.length() < bodySize) {
                  builder.append(",");
                } else {
                  break;
                }
              }
              builder.append(" }");
              bodies[i] = builder.toString().getBytes(CHARSET);
            });
  }

  // based on
  // https://stackoverflow.com/questions/41107/how-to-generate-a-random-alpha-numeric-string
  private static String random(Random random, char[] buf, int maxSize, char[] symbols) {
    // e.g. max size = 20, generates something between 5 and 20
    int size = random.nextInt(maxSize - 4) + 5;
    for (int idx = 0; idx < size; ++idx) {
      buf[idx] = symbols[random.nextInt(symbols.length)];
    }
    return new String(buf, 0, size);
  }

  @Override
  public MessageEnvelope create(int sequenceNumber) {
    return new MessageEnvelope(
        bodies[ThreadLocalRandom.current().nextInt(bodies.length)], CONTENT_TYPE, ZERO);
  }

  // for testing
  byte[][] bodies() {
    return bodies;
  }
}
