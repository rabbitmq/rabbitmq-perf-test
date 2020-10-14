// Copyright (c) 2007-2020 VMware, Inc. or its affiliates.  All rights reserved.
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class PerfUtil {
    public static void setValue(Object obj, Object name, Object value) {
        if (name == null || name.toString().isEmpty()) {
            throw new IllegalArgumentException("Property name must be specified");
        }
        try {
            String setterName;
            if (name.toString().length() > 1) {
                setterName = "set" + name.toString().substring(0, 1).toUpperCase()
                        + name.toString().substring(1);
            } else {
                setterName = "set" + name.toString().toUpperCase();
            }
            for (Method method : obj.getClass().getDeclaredMethods()) {
                if (method.getName().equals(setterName)) {
                    method.invoke(obj, convert(method.getParameterTypes()[0], value));
                    return;
                }
            }
            throw new RuntimeException("Could not find property " + name + " in " + obj.getClass());
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static Object convert(Class<?> targetType, Object value) {
        if (targetType.equals(value) || targetType.isAssignableFrom(value.getClass())) {
            return value;
        } else {
            if (isInt(targetType)) {
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                } else {
                    return Integer.valueOf(value.toString());
                }
            } else if (isFloat(targetType)) {
                if (value instanceof Number) {
                    return ((Number) value).floatValue();
                } else {
                    return Float.valueOf(value.toString());
                }
            } else if (isLong(targetType)) {
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                } else {
                    return Long.valueOf(value.toString());
                }
            }
        }
        return value;
    }

    private static boolean isInt(Class<?> targetType) {
        return (targetType.equals(Integer.class) || "int".equals(targetType.getSimpleName()));
    }

    private static boolean isFloat(Class<?> targetType) {
        return (targetType.equals(Float.class) || "float".equals(targetType.getSimpleName()));
    }

    private static boolean isLong(Class<?> targetType) {
        return (targetType.equals(Long.class) || "long".equals(targetType.getSimpleName()));
    }
}