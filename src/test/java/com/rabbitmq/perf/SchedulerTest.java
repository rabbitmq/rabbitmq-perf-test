package com.rabbitmq.perf;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 *
 */
public class SchedulerTest {

    public static void main(String[] args) {
        args = "-x 100 -y 100 --queue-pattern 'perf-test-%d' --queue-pattern-from 1 --queue-pattern-to 100 -P 10 -prsd 10".split(" ");
        PerfTest.main(args);
//        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
//        IntStream.range(0, 10).forEach(i -> {
//            executor.scheduleAtFixedRate(() -> {
//                System.out.println("executing "+ i);
//            }, new Random().nextInt(10) + 1, 2, TimeUnit.SECONDS);
//        });
    }

}
