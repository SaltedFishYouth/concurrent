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
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * A {@linkplain BlockingQueue blocking queue} in which each insert
 * operation must wait for a corresponding remove operation by another
 * thread, and vice versa.  A synchronous queue does not have any
 * internal capacity, not even a capacity of one.  You cannot
 * {@code peek} at a synchronous queue because an element is only
 * present when you try to remove it; you cannot insert an element
 * (using any method) unless another thread is trying to remove it;
 * you cannot iterate as there is nothing to iterate.  The
 * <em>head</em> of the queue is the element that the first queued
 * inserting thread is trying to add to the queue; if there is no such
 * queued thread then no element is available for removal and
 * {@code poll()} will return {@code null}.  For purposes of other
 * {@code Collection} methods (for example {@code contains}), a
 * {@code SynchronousQueue} acts as an empty collection.  This queue
 * does not permit {@code null} elements.
 *
 * <p>Synchronous queues are similar to rendezvous channels used in
 * CSP and Ada. They are well suited for handoff designs, in which an
 * object running in one thread must sync up with an object running
 * in another thread in order to hand it some information, event, or
 * task.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <E> the type of elements held in this collection
 */
public class SynchronousQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     * This class implements extensions of the dual stack and dual
     * queue algorithms described in "Nonblocking Concurrent Objects
     * with Condition Synchronization", by W. N. Scherer III and
     * M. L. Scott.  18th Annual Conf. on Distributed Computing,
     * Oct. 2004 (see also
     * http://www.cs.rochester.edu/u/scott/synchronization/pseudocode/duals.html).
     * The (Lifo) stack is used for non-fair mode, and the (Fifo)
     * queue for fair mode. The performance of the two is generally
     * similar. Fifo usually supports higher throughput under
     * contention but Lifo maintains higher thread locality in common
     * applications.
     *
     * A dual queue (and similarly stack) is one that at any given
     * time either holds "data" -- items provided by put operations,
     * or "requests" -- slots representing take operations, or is
     * empty. A call to "fulfill" (i.e., a call requesting an item
     * from a queue holding data or vice versa) dequeues a
     * complementary node.  The most interesting feature of these
     * queues is that any operation can figure out which mode the
     * queue is in, and act accordingly without needing locks.
     *
     * Both the queue and stack extend abstract class Transferer
     * defining the single method transfer that does a put or a
     * take. These are unified into a single method because in dual
     * data structures, the put and take operations are symmetrical,
     * so nearly all code can be combined. The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
     *
     * The queue and stack data structures share many conceptual
     * similarities but very few concrete details. For simplicity,
     * they are kept distinct so that they can later evolve
     * separately.
     *
     * The algorithms here differ from the versions in the above paper
     * in extending them for use in synchronous queues, as well as
     * dealing with cancellation. The main differences include:
     *
     *  1. The original algorithms used bit-marked pointers, but
     *     the ones here use mode bits in nodes, leading to a number
     *     of further adaptations.
     *  2. SynchronousQueues must block threads waiting to become
     *     fulfilled.
     *  3. Support for cancellation via timeout and interrupts,
     *     including cleaning out cancelled nodes/threads
     *     from lists to avoid garbage retention and memory depletion.
     *
     * Blocking is mainly accomplished using LockSupport park/unpark,
     * except that nodes that appear to be the next ones to become
     * fulfilled first spin a bit (on multiprocessors only). On very
     * busy synchronous queues, spinning can dramatically improve
     * throughput. And on less busy ones, the amount of spinning is
     * small enough not to be noticeable.
     *
     * Cleaning is done in different ways in queues vs stacks.  For
     * queues, we can almost always remove a node immediately in O(1)
     * time (modulo retries for consistency checks) when it is
     * cancelled. But if it may be pinned as the current tail, it must
     * wait until some subsequent cancellation. For stacks, we need a
     * potentially O(n) traversal to be sure that we can remove the
     * node, but this can run concurrently with other threads
     * accessing the stack.
     *
     * While garbage collection takes care of most node reclamation
     * issues that otherwise complicate nonblocking algorithms, care
     * is taken to "forget" references to data, other nodes, and
     * threads that might be held on to long-term by blocked
     * threads. In cases where setting to null would otherwise
     * conflict with main algorithms, this is done by changing a
     * node's link to now point to the node itself. This doesn't arise
     * much for Stack nodes (because blocked threads do not hang on to
     * old head pointers), but references in Queue nodes must be
     * aggressively forgotten to avoid reachability of everything any
     * node has ever referred to since arrival.
     */

    /**
     * Shared internal API for dual stacks and queues.
     */
    abstract static class Transferer<E> {
        /**
         * Performs a put or take.
         *
         * @param e if non-null, the item to be handed to a consumer;
         *          if null, requests that transfer return an item
         *          offered by producer. 可以为null.表示是一个REQUEST类型的请求 否则 是DATA类型的请求
         * @param timed if this operation should timeout true:表示制定了超时时间 nanos为超时时间限制(纳秒) false:表示不支持超时，一直等待到匹配为止
         * @param nanos the timeout, in nanoseconds
         * @return if non-null, the item provided or received; if null,
         *         the operation failed due to timeout or interrupt --
         *         the caller can distinguish which of these occurred
         *         by checking Thread.interrupted.
         *         E 如果不为null 都表示匹配车成功 (DATA请求返回put的数据 )为null 表示请求超时或者中断
         */
        abstract E transfer(E e, boolean timed, long nanos);
    }

    /**
     * 为什么需要自旋这个操作 因为线程挂起 在cpu角度看 是非常耗费资源的，涉及用户态和内核态的切换
     * 如果自旋期间匹配到就直接返回了，到达一定指标后还是会挂起
     */

    /** The number of CPUs, for spin control
     * 运行平台的cpu数量 当平台只有一个cpu 就不需要自旋了直接挂起
     */
    static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     * 表示指定超时时间的话：当前线程的最大自旋次数
     * 只有一个cpu 自旋次数为0 否则指定是 32次(经验值)
     */

    static final int maxTimedSpins = (NCPUS < 2) ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     * 未指定超时限制的话 线程等待的自旋次数 是16倍
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     * 如果请求是指定超时限制的话，如果anos< 1000纳秒时 禁止挂起，挂起再唤醒成本太高还不如自旋
     */
    static final long spinForTimeoutThreshold = 1000L;

    /** Dual stack */
    /**
     * 非公平模式的实现 ，内部数据结构是 栈
     */
    static final class TransferStack<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual stack algorithm, differing,
         * among other ways, by using "covering" nodes rather than
         * bit-marked pointers: Fulfilling operations push on marker
         * nodes (with FULFILLING bit set in mode) to reserve a spot
         * to match a waiting node.
         */

        /* Modes for SNodes, ORed together in node fields */
        /** Node represents an unfulfilled consumer */
        /** 表示Node 类型是 请求类型*/
        static final int REQUEST    = 0;
        /** Node represents an unfulfilled producer */
        /** 表示Node 类型是 数据类型*/
        static final int DATA       = 1;
        /** Node is fulfilling another unfulfilled DATA or REQUEST */
        /** 表示node 类型是 匹配中类型
         * 栈顶元素与 当前请类型 不相同，入栈就会修改成 FULFILLING 类型
         *
         */
        static final int FULFILLING = 2;

        /** Returns true if m has fulfilling bit set.
         * 判断是否是  FULFILLING状态
         */
        static boolean isFulfilling(int m) { return (m & FULFILLING) != 0; }

        /** Node class for TransferStacks. */
        static final class SNode {
            //指向下一个栈帧
            volatile SNode next;        // next node in stack
            //与当前node匹配的节点
            volatile SNode match;       // the node matched to this
            //自旋未被匹配成功，挂起node的对应线程，保存线程使用
            volatile Thread waiter;     // to control park/unpark
            //数据域，如果 data 不为空，就是DATA类型  否则 REQUEST类型
            Object item;                // data; or null for REQUESTs
            //表示当前Node 的类型【DATA,REQUEST,FULFILLING】
            int mode;
            // Note: item and mode fields don't need to be volatile
            // since they are always written before, and read after,
            // other volatile/atomic operations.

            SNode(Object item) {
                this.item = item;
            }

            //cas 方式修改node对象的next对象
            boolean casNext(SNode cmp, SNode val) {
                //优化：cmp==next 先判断的原因，因为 cas指令 在平台执行时，同一时刻只有一个线程执行
                //如果Java中 先判断就比较快
                return cmp == next &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * Tries to match node s to this node, if so, waking up thread.
             * Fulfillers call tryMatch to identify their waiters.
             * Waiters block until they have been matched.
             *
             *  尝试匹配 调用tryMatch() 的是现在入栈栈顶的 下一个节点，传入的是 刚入栈的栈顶节点
             *
             * @param s the node to match 刚入栈的栈顶节点
             * @return true if successfully matched to s 匹配成功返回true
             */
            boolean tryMatch(SNode s) {
                //match == null 成立说明 node 并未与任何节点发生匹配
                //UNSAFE.compareAndSwapObject(this, matchOffset, null, s) 修改成功 将 匹配节点cas 修改
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
                    //cas 修改成功了
                    Thread w = waiter;
                    //判断 栈顶节点的下个节点 是否被挂起
                    if (w != null) {    // waiters need at most one unpark
                        //挂起了 设置 线程为null 并唤醒该线程
                        waiter = null;
                        LockSupport.unpark(w);
                    }
                    return true;
                }
                return match == s;
            }

            /**
             * Tries to cancel a wait by matching node to itself.
             * 尝试取消 例如带超时时间 超时后 调用
             */
            void tryCancel() {
                // 将match 匹配节点设置为自己本身 表示 取消状态  后续其他地方会 移除出栈
                UNSAFE.compareAndSwapObject(this, matchOffset, null, this);
            }

            /**
             * 判断是否是 取消状态 （如果match 是本身 说明是取消状态）
             * @return
             */
            boolean isCancelled() {
                return match == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long matchOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = SNode.class;
                    matchOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("match"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** The head (top) of the stack */
        //表示是栈顶
        volatile SNode head;

        /**
         * cas 设置栈顶
         *
         * @param h
         * @param nh
         * @return
         */
        boolean casHead(SNode h, SNode nh) {
            //使用了优化
            return h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh);
        }

        /**
         * Creates or resets fields of a node. Called only from transfer
         * where the node to push on stack is lazily created and
         * reused when possible to help reduce intervals between reads
         * and CASes of head and to avoid surges of garbage when CASes
         * to push nodes fail due to contention.
         * @param s SNode 当这个引用为 null时会创建 一个SNode 对象，并且赋值这个引用
         * @param e item
         * @param next 当前栈节点的下一个栈帧
         * @param mode 节点类型 [DATA,REQUEST,FULFILLING]
         */
        static SNode snode(SNode s, Object e, SNode next, int mode) {
            if (s == null) s = new SNode(e);
            s.mode = mode;
            s.next = next;
            return s;
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /*
             * Basic algorithm is to loop trying one of three actions:
             *
             * 1. If apparently empty or already containing nodes of same
             *    mode, try to push node on stack and wait for a match,
             *    returning it, or null if cancelled.
             *
             * 2. If apparently containing node of complementary mode,
             *    try to push a fulfilling node on to stack, match
             *    with corresponding waiting node, pop both from
             *    stack, and return matched item. The matching or
             *    unlinking might not actually be necessary because of
             *    other threads performing action 3:
             *
             * 3. If top of stack already holds another fulfilling node,
             *    help it out by doing its match and/or pop
             *    operations, and then continue. The code for helping
             *    is essentially the same as for fulfilling, except
             *    that it doesn't return the item.
             */
            //包装当前线程的node
            SNode s = null; // constructed/reused as needed
            //(e == null) 说明是REQUEST 类型
            int mode = (e == null) ? REQUEST : DATA;

            //自旋
            for (;;) {
                //栈顶元素
                SNode h = head;

                //CASE 1:说明是空栈或者是同类型 直接压栈
                if (h == null || h.mode == mode) {  // empty or same-mode
                    //当前请求是 指定超时限制，又没设时间，表示不支持 阻塞等待
                    if (timed && nanos <= 0) {      // can't wait
                        //说明 栈顶是取消状态 协助栈顶出栈
                        if (h != null && h.isCancelled())
                            //将栈顶 cas 设置为栈顶的下一个栈元素
                            casHead(h, h.next);     // pop cancelled node
                        else
                            //大部分走这 直接返回
                            return null;
                    }
                    //(当前栈顶为 空 或者 栈顶类型与当前请求类型一直) 且 支持阻塞等待
                    //casHead(h, s = snode(s, e, h, mode)) 入栈操作
                    else if (casHead(h, s = snode(s, e, h, mode))) {
                        //入栈成功
                        //做等待匹配的逻辑 包含自旋等待 与 超时后挂起
                        //1、正常情况返回匹配节点
                        //2、取消情况返回当前节点
                        SNode m = awaitFulfill(s, timed, nanos);
                        //说明 是取消状态
                        if (m == s) {               // wait was cancelled
                            //将取消状态节点出栈
                            clean(s);
                            //取消状态返回null
                            return null;
                        }
                        //执行到这 说明当前node 被匹配了
                        //说明 当前node 和 node 匹配的栈顶节点 还未出栈
                        if ((h = head) != null && h.next == s)
                            //将当前node 和匹配node 出栈
                            casHead(h, s.next);     // help s's fulfiller
                        //如果是 request 类型 直接返回 匹配节点的数据 否则返回 当前节点的数据
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    }
                }
                //CASE 2: 说明栈顶类型与当前请求的类型 不是同类型请求
                //(DATA - REQUEST) (REQUEST - DATA) (FULFILLING -DATA/REQUEST)
                else if (!isFulfilling(h.mode)) { // try to fulfill
                    //说明当前栈顶是 取消状态 当前线程 协助出栈
                    if (h.isCancelled())            // already cancelled
                        casHead(h, h.next);         // pop and retry
                    //压栈 并且 将当前节点 设置为 FULFILLING 入栈
                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                        //压栈成功 做匹配操作
                        for (;;) { // loop until matched or waiters disappear
                            //m 匹配节点
                            SNode m = s.next;       // m is s's match
                            //匹配节点为null  因为有可能是 超时然后 取消状态出栈了 就导致为null
                            if (m == null) {        // all waiters are gone
                                //将本次的节点清空
                                casHead(s, null);   // pop fulfill node
                                s = null;           // use new node next time
                                //这里会回到最外层的 重新自旋，可能会插入新节点
                                break;              // restart main loop
                            }

                            //匹配节点不为空
                            // 获取 匹配节点的下一个节点
                            SNode mn = m.next;
                            //如果匹配成功
                            if (m.tryMatch(s)) {
                                //成对出栈
                                casHead(s, mn);     // pop both s and m
                                return (E) ((mode == REQUEST) ? m.item : s.item);
                            } else                  // lost match
                                //强制出栈 有可能匹配节点 超时了
                                s.casNext(m, mn);   // help unlink
                        }
                    }
                }
                //CASE 3:栈顶类型是 FULFILLING 模式 表示栈顶和栈顶下面的栈帧正在发生匹配
                //当前请求需要做协助工作
                else {                            // help a fulfiller
                    //h是 FULFILLING m是 FULFILLING匹配节点
                    SNode m = h.next;               // m is h's match
                    if (m == null)                  // waiter is gone
                        //清空
                        casHead(h, null);           // pop fulfilling node
                    else {
                        SNode mn = m.next;
                        //匹配成 结对出栈
                        if (m.tryMatch(h))          // help match
                            casHead(h, mn);         // pop both h and m
                        else                        // lost match
                            //h强制出栈 重新自旋
                            h.casNext(m, mn);       // help unlink
                    }
                }
            }
        }

        /**
         * Spins/blocks until node s is matched by a fulfill operation.
         *
         * @param s the waiting node 当前请求 node
         * @param timed true if timed wait 是否支持超时
         * @param nanos timeout value 指定超时时间（纳秒）
         * @return matched node, or s if cancelled
         */
        SNode awaitFulfill(SNode s, boolean timed, long nanos) {
            /*
             * When a node/thread is about to block, it sets its waiter
             * field and then rechecks state at least one more time
             * before actually parking, thus covering race vs
             * fulfiller noticing that waiter is non-null so should be
             * woken.
             *
             * When invoked by nodes that appear at the point of call
             * to be at the head of the stack, calls to park are
             * preceded by spins to avoid blocking when producers and
             * consumers are arriving very close in time.  This can
             * happen enough to bother only on multiprocessors.
             *
             * The order of checks for returning out of main loop
             * reflects fact that interrupts have precedence over
             * normal returns, which have precedence over
             * timeouts. (So, on timeout, one last check for match is
             * done before giving up.) Except that calls from untimed
             * SynchronousQueue.{poll/offer} don't check interrupts
             * and don't wait at all, so are trapped in transfer
             * method rather than calling awaitFulfill.
             */
            //等待的截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            //获取当前的线程
            Thread w = Thread.currentThread();
            // 表示当前请求线程在下面自旋检查中的 自旋次数 如果达到 spins 自旋次数还未匹配成功 则挂起线程
            int spins = (shouldSpin(s) ?
                        //timed 指定了超时时间用 maxTimedSpins ==32 否则 32 * 16
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            //自旋检查：1、是否匹配 2、是否超时  3、是否中断
            for (;;) {
                //w.isInterrupted() 条件成立说明 线程收到中断信号
                if (w.isInterrupted())
                    //将请求本节点设置为取消状态
                    s.tryCancel();

                //获取当前请求节点的 匹配节点
                SNode m = s.match;
                //条件成立 说明已经匹配了 直接返回匹配的值 或者 指向自己是取消状态
                if (m != null)
                    return m;


                //制定了 超时限制
                if (timed) {
                    //距离超时还有多少纳秒
                    nanos = deadline - System.nanoTime();
                    //条件成立说明 超时了
                    if (nanos <= 0L) {
                        //设置为取消状态 match设置为自己 接着自旋然后退出了
                        s.tryCancel();
                        continue;
                    }
                }
                //自旋次数大于0 还可以自旋检查
                if (spins > 0)
                    //自旋次数递减
                    spins = shouldSpin(s) ? (spins-1) : 0;
                //前提：自旋次数为0 了不能接着自旋，是否设置了挂起线程 没就设置挂起线程
                else if (s.waiter == null)
                    s.waiter = w; // establish waiter so can park next iter
                //前提自旋达到最大次数 且 设置了 挂起检查  判断是否支持超时 不支持直接挂起 知道外部线程 unpark 唤醒
                else if (!timed)
                    LockSupport.park(this);
                //超时时间是否大于 1000纳秒 是的话 挂起 否则 挂起的成本 大于空转自旋
                else if (nanos > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Returns true if node s is at head or there is an active
         * fulfiller.
         * 是否需要自旋 true 是
         */
        boolean shouldSpin(SNode s) {
            //获取栈顶
            SNode h = head;
            //h == s 当前s就是栈顶
            //h == null s是自旋检查期间 又来个 与s匹配的请求 双双出栈 就会成立
            //              为什么不可能是 空栈呢，因为调用这里外面 已经把当前节点放入，就算空栈也会是头节点是s
            //isFulfilling(h.mode) 前提s 不是栈顶元素 并且栈顶元素正在匹配中 栈顶下面的元素允许自旋
            return (h == s || h == null || isFulfilling(h.mode));
        }

        /**
         * Unlinks s from the stack.
         */
        void clean(SNode s) {
            //清空数据域
            s.item = null;   // forget item
            //释放线程引用
            s.waiter = null; // forget thread

            /*
             * At worst we may need to traverse entire stack to unlink
             * s. If there are multiple concurrent calls to clean, we
             * might not see s if another thread has already removed
             * it. But we can stop when we see any node known to
             * follow s. We use s.next unless it too is cancelled, in
             * which case we try the node one past. We don't check any
             * further because we don't want to doubly traverse just to
             * find sentinel.
             */
            //检查 取消节点的截止位置
            SNode past = s.next;
            if (past != null && past.isCancelled())
                past = past.next;

            // Absorb cancelled nodes at head
            SNode p;
            while ((p = head) != null && p != past && p.isCancelled())
                casHead(p, p.next);

            // Unsplice embedded nodes
            while (p != null && p != past) {
                SNode n = p.next;
                if (n != null && n.isCancelled())
                    p.casNext(n, n.next);
                else
                    p = n;
            }
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferStack.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Dual Queue
     * 公平模式同步队列
     */
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         */

        /** Node class for TransferQueue. */
        static final class QNode {
            //指向当前节点的下一个节点 组装链表使用
            volatile QNode next;          // next node in queue
            //数据域 Node 代表的是DATA类型 item 表示数据 ,否则Node 代表的REQUEST 类型item ==null
            volatile Object item;         // CAS'ed to or from null
            //对应Node 未匹配到节点时，对应的线程 最终挂起，挂起之前保留引用到 waiter
            volatile Thread waiter;       // to control park/unpark
            //是否是DATA类型 节点
            final boolean isData;

            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }

            /**
             * cas 修改当前节点的 下一个节点
             * @param cmp
             * @param val
             * @return
             */
            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * cas 修改当前节点的数据域
             * @param cmp
             * @param val
             * @return
             */
            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
            }

            /**
             * Tries to cancel by CAS'ing ref to this as item.
             * 取消当前节点
             * 取消状态的 数据域指向自己
             */
            void tryCancel(Object cmp) {
                UNSAFE.compareAndSwapObject(this, itemOffset, cmp, this);
            }

            /**
             * 是否是取消节点
             * @return
             */
            boolean isCancelled() {
                return item == this;
            }

            /**
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             * 判断 当前节点是否 不在 队列内  next 指向自己 说明出队了
             */
            boolean isOffList() {
                return next == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long itemOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = QNode.class;
                    itemOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("item"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** Head of queue  队列的 dummy 节点*/
        transient volatile QNode head;
        /** Tail of queue  队列的 尾 节点*/
        transient volatile QNode tail;
        /**
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         * 表示被清理节点的 前驱节点 因为入队操作是两步
         */
        transient volatile QNode cleanMe;

        TransferQueue() {
            QNode h = new QNode(null, false); // initialize to dummy node.
            head = h;
            tail = h;
        }

        /**
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         * 设置头节电，蕴含操作 头节点出队
         */
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh))
                h.next = h; // forget old next
        }

        /**
         * Tries to cas nt as new tail.
         * 更新队尾节点
         */
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                UNSAFE.compareAndSwapObject(this, tailOffset, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         * cas 修改 cleanMe
         */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                UNSAFE.compareAndSwapObject(this, cleanMeOffset, cmp, val);
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             *
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             */
            //s 指向当前请求 node
            QNode s = null; // constructed/reused as needed
            //是否是 DATA操作
            boolean isData = (e != null);
            //自旋
            for (;;) {
                QNode t = tail;
                QNode h = head;
                if (t == null || h == null)         // saw uninitialized value
                    continue;                       // spin
                //CASE 1:  队列是空队列 当前请求是第一个节点 或队尾是相同类型节点 直接入队
                if (h == t || t.isData == isData) { // empty or same-mode
                    //tn 队尾的 后继节点
                    QNode tn = t.next;
                    //可能因为多线程 其他线程 修改了tail 回到自旋
                    if (t != tail)                  // inconsistent read
                        continue;
                    //可能因为多线程 其他线程 修改了tail的next 节点 但未把 tail 的引用修改（入队分两步，先修改t.next，再把tail指针修改） 回到自旋
                    if (tn != null) {               // lagging tail
                        //协助更新tail 指向 新的队尾 即第二步操作
                        advanceTail(t, tn);
                        continue;
                    }
                    //是否支持 超时等待 上层 可能是 offer()无参 方法进来的
                    if (timed && nanos <= 0)        // can't wait
                        //未匹配到直接返回
                        return null;

                    //当前节点 未创建Node
                    if (s == null)
                        //创建node 并赋值
                        s = new QNode(e, isData);
                    //cas 将s 追加在 t 后面 失败说明 已经有新值追加在后面了直接 退出当前循环 接着自旋
                    if (!t.casNext(null, s))        // failed to link in
                        continue;

                    //走到这说明已经完成了第一步 设置t.next 接着更新 tail的引用为当前节点
                    advanceTail(t, s);              // swing tail and wait

                    //当前节点 等待匹配状态
                    Object x = awaitFulfill(s, e, timed, nanos);

                    //执行到这 是匹配成功
                    //1、唤醒的 匹配他的线程帮他出队了 再唤醒他的
                    //2、自旋出来的 可能自旋先走到这 匹配他的线程还没帮他出队

                    //item 等于自己 说明是取消状态 直接 出队逻辑
                    if (x == s) {                   // wait was cancelled
                        clean(t, s);
                        return null;
                    }

                    //走到这说明已经匹配成功了

                    //s 还在队列中 说明匹配成功后还未出队  需要做出队逻辑
                    //特殊说明 如果是被唤醒的节点 在 唤醒线程那 已经出队了 这里就不会进来 自旋的有可能进来
                    if (!s.isOffList()) {           // not already unlinked
                        //将s 设置为dummy 节点 并且将head.next 出队
                        advanceHead(t, s);          // unlink if head
                        //说明是 requestr 类型且获取到数据
                        if (x != null)              // and forget fields
                            //将自己设置为取消
                            s.item = s;
                        s.waiter = null;
                    }
                    return (x != null) ? (E)x : e;

                }
                //前提  不是空队列 且 队尾不是同类型节点
                //CASE 2:
                else {                            // complementary-mode
                    //m 是真正的队头  请求节点需要与 队头匹配 因为是 公平队列
                    QNode m = h.next;               // node to fulfill
                    //说明 并发 已经添加了新的tail 或者 已经并发 将h匹配出队了   h != head 已经并发 将h匹配出队了
                    if (t != tail || m == null || h != head)
                        continue;                   // inconsistent read

                    //走到这 t m h 不是过期数据
                    //获取匹配节点的数据
                    Object x = m.item;
                    //isData == (x != null) 条件成立
                    //情况 1：isData true 当前请求是data类型 true ==true  头节点的 是request 且数据域不为空 那么不是取消就是已经匹配了
                    //情况 2：isData false 当前请求是request类型  false ==false 头节点的 是data 且数据域为空 那么就是已经匹配了

                    //x == m m是取消节点
                    //!m.casItem(x, e)  当前为request m为 data 清空 data数据域 或 当前为data m为request 设置request 数据域
                    //                  修改不成功说明并发了
                    //上面的情况都说明 是过期数据 那么将m 出队
                    if (isData == (x != null) ||    // m already fulfilled
                        x == m ||                   // m cancelled
                        !m.casItem(x, e)) {         // lost CAS
                        //将m设置为head 出队 当前线程接着自旋
                        advanceHead(h, m);          // dequeue and retry
                        continue;
                    }

                    //执行到这 说明 已经匹配完成了
                    //1、将头节点出队
                    //2、并且唤醒头节点的线程
                    advanceHead(h, m);              // successfully fulfilled
                    LockSupport.unpark(m.waiter);
                    return (x != null) ? (E)x : e;
                }
            }
        }

        /**
         * Spins/blocks until node s is fulfilled.
         *
         * @param s the waiting node
         * @param e the comparison value for checking match
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched item, or s if cancelled
         */
        Object awaitFulfill(QNode s, E e, boolean timed, long nanos) {
            /* Same idea as TransferStack.awaitFulfill */
            //等待截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            //当前请求线程
            Thread w = Thread.currentThread();
            //自旋次数
            int spins = ((head.next == s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            //自旋： 1、检查状态等待匹配 2、挂起线程 3、检查状态是否中断 或 超时
            for (;;) {
                //线程收到中断信号
                if (w.isInterrupted())
                    //设置为取消状态 数据域 设置为自身
                    s.tryCancel(e);

                //获取数据域
                Object x = s.item;
                //item 有几种情况
                //DATA
                //1、item !=null 且 item！= this 表示要传递的数据
                //2、item == this 表示当前node 对应的线程 为 取消状态
                //3、item == null 表示已经有匹配节点了 并且匹配节点拿走了 item数据

                //REQUEST
                //1、item == null 为正常的状态，当前请求未匹配到对应的DATA请求
                //2、item == this 当前node对应的线程 为取消状态
                //3、item != null 且 item != this  表示当前REQUEST node 已经匹配到一个 DATA的node

                //条件成立
                //DATA
                //2、item == this 表示当前node 对应的线程 为 取消状态
                //3、item == null 表示已经有匹配节点了 并且匹配节点拿走了 item数据

                //REQUEST
                //2、item == this 当前node对应的线程 为取消状态
                //3、item != null 且 item != this  表示当前REQUEST node 已经匹配到一个 DATA的node
                if (x != e)
                    return x;

                //指定使用超时
                if (timed) {
                    //计算剩余时间
                    nanos = deadline - System.nanoTime();
                    //超时了
                    if (nanos <= 0L) {
                        //设置为取消状态
                        s.tryCancel(e);
                        continue;
                    }
                }
                //自旋次数检查 还有自旋次数接着自旋
                if (spins > 0)
                    --spins;

                //走到这自旋次数为0
                //等待线程 为设置 那么保存等待线程
                else if (s.waiter == null)
                    s.waiter = w;

                //没有指定超时 直接挂起
                else if (!timed)
                    LockSupport.park(this);
                //走到这 说明 是支持超时的
                //超时时间 nanos 大于最小时间 就挂起，没有的话 说明太小了没有必要挂起线程 不如自旋
                else if (nanos > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Gets rid of cancelled node s with original predecessor pred.
         */
        void clean(QNode pred, QNode s) {
            s.waiter = null; // forget thread
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             */
            while (pred.next == s) { // Return early if already unlinked
                QNode h = head;
                QNode hn = h.next;   // Absorb cancelled first node as head
                if (hn != null && hn.isCancelled()) {
                    advanceHead(h, hn);
                    continue;
                }
                QNode t = tail;      // Ensure consistent read for tail
                if (t == h)
                    return;
                QNode tn = t.next;
                if (t != tail)
                    continue;
                if (tn != null) {
                    advanceTail(t, tn);
                    continue;
                }
                if (s != t) {        // If not tail, try to unsplice
                    QNode sn = s.next;
                    if (sn == s || pred.casNext(s, sn))
                        return;
                }
                QNode dp = cleanMe;
                if (dp != null) {    // Try unlinking previous cancelled node
                    QNode d = dp.next;
                    QNode dn;
                    if (d == null ||               // d is gone or
                        d == dp ||                 // d is off list or
                        !d.isCancelled() ||        // d not cancelled or
                        (d != t &&                 // d not tail and
                         (dn = d.next) != null &&  //   has successor
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // d unspliced
                        casCleanMe(dp, null);
                    if (dp == pred)
                        return;      // s is already saved node
                } else if (casCleanMe(null, pred))
                    return;          // Postpone cleaning s
            }
        }

        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        private static final long tailOffset;
        private static final long cleanMeOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferQueue.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
                tailOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("tail"));
                cleanMeOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("cleanMe"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * The transferer. Set only in constructor, but cannot be declared
     * as final without further complicating serialization.  Since
     * this is accessed only at most once per public method, there
     * isn't a noticeable performance penalty for using volatile
     * instead of final here.
     */
    private transient volatile Transferer<E> transferer;

    /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
    public SynchronousQueue() {
        this(false);
    }

    /**
     * Creates a {@code SynchronousQueue} with the specified fairness policy.
     *
     * @param fair if true, waiting threads contend in FIFO order for
     *        access; otherwise the order is unspecified.
     */
    public SynchronousQueue(boolean fair) {
        transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
    }

    /**
     * Adds the specified element to this queue, waiting if necessary for
     * another thread to receive it.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, false, 0) == null) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * up to the specified wait time for another thread to receive it.
     *
     * @return {@code true} if successful, or {@code false} if the
     *         specified waiting time elapses before a consumer appears
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, true, unit.toNanos(timeout)) != null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    /**
     * Inserts the specified element into this queue, if another thread is
     * waiting to receive it.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else
     *         {@code false}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        return transferer.transfer(e, true, 0) != null;
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * for another thread to insert it.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    public E take() throws InterruptedException {
        E e = transferer.transfer(null, false, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary up to the specified wait time, for another thread
     * to insert it.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is present
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = transferer.transfer(null, true, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, if another thread
     * is currently making an element available.
     *
     * @return the head of this queue, or {@code null} if no
     *         element is available
     */
    public E poll() {
        return transferer.transfer(null, true, 0);
    }

    /**
     * Always returns {@code true}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return {@code true}
     */
    public boolean isEmpty() {
        return true;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int size() {
        return 0;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int remainingCapacity() {
        return 0;
    }

    /**
     * Does nothing.
     * A {@code SynchronousQueue} has no internal capacity.
     */
    public void clear() {
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element
     * @return {@code false}
     */
    public boolean contains(Object o) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element to remove
     * @return {@code false}
     */
    public boolean remove(Object o) {
        return false;
    }

    /**
     * Returns {@code false} unless the given collection is empty.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false} unless given collection is empty
     */
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code null}.
     * A {@code SynchronousQueue} does not return elements
     * unless actively waited on.
     *
     * @return {@code null}
     */
    public E peek() {
        return null;
    }

    /**
     * Returns an empty iterator in which {@code hasNext} always returns
     * {@code false}.
     *
     * @return an empty iterator
     */
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    /**
     * Returns an empty spliterator in which calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return an empty spliterator
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.emptySpliterator();
    }

    /**
     * Returns a zero-length array.
     * @return a zero-length array
     */
    public Object[] toArray() {
        return new Object[0];
    }

    /**
     * Sets the zeroeth element of the specified array to {@code null}
     * (if the array has non-zero length) and returns it.
     *
     * @param a the array
     * @return the specified array
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        if (a.length > 0)
            a[0] = null;
        return a;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /*
     * To cope with serialization strategy in the 1.5 version of
     * SynchronousQueue, we declare some unused classes and fields
     * that exist solely to enable serializability across versions.
     * These fields are never used, so are initialized only if this
     * object is ever serialized or deserialized.
     */

    @SuppressWarnings("serial")
    static class WaitQueue implements java.io.Serializable { }
    static class LifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3633113410248163686L;
    }
    static class FifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3623113410248163686L;
    }
    private ReentrantLock qlock;
    private WaitQueue waitingProducers;
    private WaitQueue waitingConsumers;

    /**
     * Saves this queue to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        boolean fair = transferer instanceof TransferQueue;
        if (fair) {
            qlock = new ReentrantLock(true);
            waitingProducers = new FifoWaitQueue();
            waitingConsumers = new FifoWaitQueue();
        }
        else {
            qlock = new ReentrantLock();
            waitingProducers = new LifoWaitQueue();
            waitingConsumers = new LifoWaitQueue();
        }
        s.defaultWriteObject();
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        if (waitingProducers instanceof FifoWaitQueue)
            transferer = new TransferQueue<E>();
        else
            transferer = new TransferStack<E>();
    }

    // Unsafe mechanics
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }

}
