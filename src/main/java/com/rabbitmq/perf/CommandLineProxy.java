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

package com.rabbitmq.perf;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.util.function.Function;
import java.util.function.Supplier;

import static java.lang.String.valueOf;

/**
 * A proxy to add behavior around a {@link CommandLine}.
 * It implements only the few methods used in {@link PerfTest}.
 * This class has been introduced to easily make environment
 * variables override or directly set arguments.
 *
 * {@link CommandLine} doesn't implement any interface nor
 * can be subclassed, that's why this proxy trick is used.
 */
public class CommandLineProxy {

    private final CommandLine delegate;

    private final Function<String, String> argumentLookup;

    public CommandLineProxy(final Options options, CommandLine delegate, Function<String, String> argumentLookup) {
        this.delegate = delegate;
        Function<String, String> optionToLongOption = option -> {
            for (Object optObj : options.getOptions()) {
                Option opt = (Option) optObj;
                if (opt.getOpt().equals(option)) {
                    return opt.getLongOpt();
                }
            }
            return null;
        };
        this.argumentLookup = optionToLongOption
            .andThen(longOption -> longOption == null ? null : argumentLookup.apply(longOption));
    }

    public boolean hasOption(char opt) {
        return override(valueOf(opt), () -> delegate.hasOption(opt), Boolean.class);
    }

    public boolean hasOption(String opt) {
        return override(opt, () -> delegate.hasOption(opt), Boolean.class);
    }

    public String getOptionValue(char opt, String def) {
        return override(valueOf(opt), () -> delegate.getOptionValue(opt, def), String.class);
    }

    public String getOptionValue(String opt, String def) {
        return override(opt, () -> delegate.getOptionValue(opt, def), String.class);
    }

    public String[] getOptionValues(char opt) {
        return override(valueOf(opt), () -> delegate.getOptionValues(opt), String[].class);
    }

    public String getOptionValue(char opt) {
        return override(valueOf(opt), () -> delegate.getOptionValue(opt), String.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T override(String opt, Supplier<T> argumentValue, Class<T> type) {
        String value = argumentLookup.apply(opt);
        if (value == null) {
            return argumentValue.get();
        } else {
            if (Boolean.class.equals(type)) {
                return (T) Boolean.valueOf(value);
            } else if (String[].class.equals(type)) {
                return (T) value.split(",");
            } else {
                return (T) value;
            }
        }
    }
}
