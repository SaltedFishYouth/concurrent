package cn.lsx.concurrent;

import java.util.concurrent.*;

/**
 * 
 * @author lsx
 * @date 2021/09/15 17:55
 **/
public class FutureTaskDemo {
    public static void main(String[] args) {
        ExecutorService threadPool = Executors.newFixedThreadPool(5);
        Future future = threadPool.submit(new RunnableTask());
    }
    private static class RunnableTask implements Runnable {

        @Override
        public void run() {

        }
    }
}
