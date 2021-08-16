package cn.lsx.concurrent.longadder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author linShengxi
 * @date 2021/7/30
 */

public class LongAdderVSAtomicLongDemo {
    public static void main(String[] args) {
        longAdderVSAtomicLong(1, 10000000);

        longAdderVSAtomicLong(10, 10000000);

        longAdderVSAtomicLong(20, 10000000);

        longAdderVSAtomicLong(40, 10000000);

        longAdderVSAtomicLong(80, 10000000);
    }

    /**
     * @param threadCount 开启线程数
     * @param times       累加次数
     */
    private static void longAdderVSAtomicLong(int threadCount, int times) {
        try {
            System.out.println("threadCount:" + threadCount + ",times:" + times);
            long start = System.currentTimeMillis();
            longAdder(threadCount, times);
            System.out.println("longAdder elapse:" + (System.currentTimeMillis() - start) + "ms");

            start = System.currentTimeMillis();
            atomicLong(threadCount, times);
            System.out.println("atomicLong elapse:" + (System.currentTimeMillis() - start) + "ms");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    static void longAdder(int threadCount, int times) throws InterruptedException {
        LongAdder longAdder = new LongAdder();
        List<Thread> list = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            list.add(new Thread(() -> {
                for (int j = 0; j < times; j++) {
                    longAdder.increment();
                }
            }));
        }
        for (Thread thread : list) {
            thread.start();
        }
        for (Thread thread : list) {
            thread.join();
        }
    }

    static void atomicLong(int threadCount, int times) throws InterruptedException {
        AtomicLong atomicLong = new AtomicLong();
        List<Thread> list = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            list.add(new Thread(() -> {
                for (int j = 0; j < times; j++) {
                    atomicLong.incrementAndGet();
                }
            }));
        }
        for (Thread thread : list) {
            thread.start();
        }
        for (Thread thread : list) {
            thread.join();
        }
    }
}
