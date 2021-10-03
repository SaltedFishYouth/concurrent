/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    //表示当前task状态
    private volatile int state;
    //当前任务尚未执行
    private static final int NEW          = 0;
    //当前任务正在结束，稍微完全结束，一种临界状态 [kəmˈpliːtɪŋ]
    private static final int COMPLETING   = 1;
    //当前任务正常结束  [ˈnɔːrml]
    private static final int NORMAL       = 2;
    //当前任务执行中发生了异常。内部封装的callable.run() 向上抛出异常了
    private static final int EXCEPTIONAL  = 3;
    //当前任务被取消了  [ˈkænsld]
    private static final int CANCELLED    = 4;
    //当前任务中断中   [ˌɪntəˈrʌptɪŋ]
    private static final int INTERRUPTING = 5;
    //当前任务已中断    [ˌɪntəˈrʌptɪd]
    private static final int INTERRUPTED  = 6;

    /** The underlying callable; nulled out after running */
    //submit(runnable/callable) runnable 使用适配器模式 适配成callable了
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    // 正常情况下：任务正常执行结果，outcome保存执行结果
    // 非正常情况：callable向上抛出异常，outcome保存异常
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    //当前任务被线程执行期间，保存当前执行任务的线程对象引用
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    //因为会有很多线程去get当前任务的结果，所以 这里使用了一种数据结构stack
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        //正常情况下outcome
        Object x = outcome;
        //条件成立：当前任务状态正常结束
        if (s == NORMAL)
            return (V)x;
        //被中断取消状态
        if (s >= CANCELLED)
            throw new CancellationException();
        //异常情况 说明callable.run有bug 抛出异常
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        //使用适配器模式 将runnable 转化为 callable 外部线程通过get获取
        //执行结束时，执行结果为传进来的值或者
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        //条件一：state == NEW 当前任务处于运行中或线程池任务队列中
        //条件二：将状态设置为 中断中 或 取消
        //取反 不是运行中或队列中的 或 cas设置失败直接返回false
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            //前置条件 刚刚状态设置为中断中
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    //当前FutureTask的线程有可能是null :当前任务在队列中，还没有线程获取到它
                    if (t != null)
                        //给ruanner线程一个中断信号 走中断逻辑，如果程序不是响应中断 啥也不会发生
                        t.interrupt();
                } finally { // final state
                    //修改为中断完成
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            //唤醒所有get 阻塞线程
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
        //获取当前任务状态
        int s = state;
        //条件成立：未执行、正在执行、正完成、调用get的外部线程会被阻塞在get方法上
        if (s <= COMPLETING)
            //返回task 状态 可能线程已经在里面睡了一会
            s = awaitDone(false, 0L);
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        //设置状态为完成中，cas设置失败的情况：在执行完成前 外部线程来不及 canncle掉了 很小的概率事件
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            //将结果赋值给 outcome 后马上将任务修改为normal状态
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            //回头再说
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        //设置状态为完成中，cas设置失败的情况：在执行完成前 外部线程来不及 canncle掉了 很小的概率事件
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            //将状态修改为异常状态
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    public void run() {
        //条件一：状态不是初始化状态 说明被执行过 或者cancel了
        //条件二：运行状态cas设置失败，当前任务被其他线程抢占了
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            //条件一：防止空指针
            //条件二：防止被外部线程cancel掉当前任务
            if (c != null && state == NEW) {
                V result;
                //true：callable.run 执行成功没有发生异常
                //false：callable.run 执行失败 发生异常
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    result = null;
                    ran = false;

                    setException(ex);
                }
                if (ran)
                    //c.call执行结束设置结果
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            int s = state;
            if (s >= INTERRUPTING)
                //回头再说 讲cancle时就明白了
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        //q指向 waiters 链表节点
        for (WaitNode q; (q = waiters) != null;) {
            //可能有竞争：将waiters设置为null(cancel取消当前任务也会触发finishCompletion方法)
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    //获取当前节点的thread
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;//helpGC
                        //唤醒当前节点对应的线程
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        done();
        //helpGC
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        //0 不带超时
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        //当前引用线程对象 封装成 waitNode对象
        WaitNode q = null;
        //是否已经压栈
        boolean queued = false;

        //自旋
        for (;;) {
            // 条件成立：说明当前线程的换新是被其他线程中唤醒的。
            //后会将tread的中断标识重置为false 下次自旋就为true
            if (Thread.interrupted()) {
                //当前线程node出栈
                removeWaiter(q);
                //抛出中断异常
                throw new InterruptedException();
            }
            //假设当前线程是被其他线程使用unpark（thread） 唤醒的正常自旋，走下面逻辑
            //获取最新的状态
            int s = state;
            //条件成立：已经有结果了
            if (s > COMPLETING) {
                //条件成立：当前线程node已经入栈 需要指向null helpGC
                if (q != null)
                    q.thread = null;
                //直接返回状态
                return s;
            }
            //条件成立：说明当前任务接近完成任务状态 这里让当前线程释放cpu，进行下一次抢占
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            // 条件成立：第一次自旋 创建waitNode对象
            else if (q == null)
                q = new WaitNode();
            // 条件成立：第二次自旋，当前线程已经创建waitNode对象 但未入栈
            else if (!queued) {
                //压栈
                q.next = waiters;
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                        waiters, q);
            }
            //条件成立：第三次自旋（假设是不超时）
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            else
                //当前get操作的线程就会被park，线程状态变为WAITING 相当于休眠了
                //除非有其他线程 将其换新或中断
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void  removeWaiter(WaitNode node) {
        if (node != null) {
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    if (q.thread != null)
                        pred = q;
                    else if (pred != null) {
                        pred.next = s;
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
