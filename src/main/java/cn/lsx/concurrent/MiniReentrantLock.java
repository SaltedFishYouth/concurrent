package cn.lsx.concurrent;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.locks.LockSupport;

/**
 * 简单模拟ReentrantLock 加锁和解锁功能
 * @author lsx
 * @date 2021/10/03 16:56
 **/
public class MiniReentrantLock {
    /**
     * 锁的是什么？
     * 资源 -> state
     * 0 表示未加锁状态
     * >0 表示当前lock是加锁状态..
     */
    private volatile int state;

    /**
     * 独占模式？
     * 同一时刻只有一个线程可以持有锁，其它的线程，在未获取到锁时，会被阻塞..
     */
    //当前独占锁的线程（占用锁线程）
    private Thread exclusiveOwnerThread;

    /**
     * 需要有两个引用是维护 阻塞队列
     * 1.Head 指向队列的头节点
     * 2.Tail 指向队列的尾节点
     */
    //比较特殊：head节点对应的线程 就是当前占用锁的线程
    private Node head;
    private Node tail;



    /**
     * 保存阻塞线程的节点
     */
    class Node{
        Node pre;
        Node next;

        Thread thread;

        public Node(Thread thread) {
            this.thread = thread;
        }

        public Node() {
        }
    }
    /**
     * 获取锁
     * 如果锁被占用，则会阻塞调用者线程，直到抢占锁为止
     *
     *
     *
     */
    public void lock() {
        acquire(1);
    }

    /**
     * 释放锁
     *
     *
     */
    public void unLock() {

    }

    /**
     * 竞争资源
     * 1、获取到锁 占用锁 返回
     * 2、获取失败阻塞当前线程
     */
    private void acquire(int arg){
        if (!tryAcquire(arg)) {
            //获取失败的逻辑
            //1、放入等待的线程队列中
            Node newNode = addWaiter();
            //2、阻塞并且 持续获取 锁
        }
    }

    /**
     * 尝试获取 锁，成功返回true 失败返回false 不会阻塞线程
     * @param arg
     * @return
     */
    private boolean tryAcquire(int arg){
        if (state == 0) {
        //当前是无锁 判断 等待队列，是否有 其他线程等待
        //没有其他线程等待则当前线程可以去获取锁，使用cas 去抢锁
        if (!hasQueuedPredecessor() && compareAndSetState(0,arg)) {
            //进到这 说明枪锁成功 那么将当前线程 放入 持锁线程中
            this.exclusiveOwnerThread = Thread.currentThread();
            return true;
        }
        } else if(exclusiveOwnerThread == Thread.currentThread()) {
            //锁重入的流程
            //说明当前线程即为持锁线程，是需要返回true的！
            int c = getState();
            c = c + arg;
            //越界判断..
            this.state = c;
            return true;
        }
        return true;
    }

    /**
     * 添加当前线程到 阻塞线程队列 并返回当前线程的节点
     * @return
     */
    private Node addWaiter(){
        Node newNode = new Node(Thread.currentThread());

        Node pred = tail;
        if (pred != null) {
            //说明不是第一个失败线程
            newNode.pre = pred;
            if (compareAndSetTail(pred,newNode)) {
                pred.next = newNode;
                return newNode;
            }
        }

        //执行到这 一： pred == null
        //        二：cas 添加失败 可能有 其他线程也在设置 那么得进自选 添加
        enq(newNode);

        return newNode;
    }
    /**
     * 自旋入队，只有成功后才返回.
     *
     * 1.tail == null 队列是空队列
     * 2.cas 设置当前newNode 为 tail 时 失败了...被其它线程抢先一步了...
     */
    private void enq(Node node) {
        for(;;) {
            //第一种情况：队列是空队列
            //==> 当前线程是第一个抢占锁失败的线程..
            //当前持有锁的线程，并没有设置过任何 node,所以作为该线程的第一个后驱，需要给它擦屁股、
            //给当前持有锁的线程 补充一个 node 作为head节点。  head节点 任何时候，都代表当前占用锁的线程。
            if(tail == null) {
                //条件成立：说明当前线程 给 当前持有锁的线程 补充 head操作成功了..
                if(compareAndSetHead(new Node())) {
                    tail = head;
                    //注意：并没有直接返回，还会继续自旋...
                }
            } else {
                //说明：当前队列中已经有node了，这里是一个追加node的过程。

                //如何入队呢？
                //1.找到newNode的前置节点 pred
                //2.更新newNode.prev = pred
                //3.CAS 更新tail 为 newNode
                //4.更新 pred.next = newNode

                //前置条件：队列已经有等待者node了，当前node 不是第一个入队的node
                Node pred = tail;
                if(pred != null) {
                    node.pre = pred;
                    //条件成立：说明当前线程成功入队！
                    if(compareAndSetTail(pred, node)) {
                        pred.next = node;
                        //注意：入队成功之后，一定要return。。
                        return;
                    }
                }
            }
        }
    }

    /**
     * 自选阻塞 获取锁
     * @param node
     * @param arg
     */
    private void acquireQueued(Node node,int arg){
        for (;;){
            //先判断 当前节点是否 是 第一个抢锁失败节点
            //是的话 尝试获取锁
            Node pre = node.pre;
            if (pre == head && tryAcquire(arg)) {
                //说明获取锁成功 设置 头结点为当前节点 ;
                setHead(node);
                //头结点的 next是没有意义的，管理
                pre.next = null; //help gc
                return;
            }
            System.out.println("线程：" + Thread.currentThread().getName() + "，挂起！");
            //将当前线程挂起！
            LockSupport.park();
            System.out.println("线程：" + Thread.currentThread().getName() + "，唤醒！");

            //什么时候唤醒被park的线程呢？
            //unlock 过程了！
        }
    }

    /**
     * 是否还有 等待线程
     * @return
     */
    private boolean hasQueuedPredecessor() {
        Node h = head;
        Node t = tail;
        Node s;

        //h != t 说明 已经有节点了
        //不成立时 有可能是 是 t ==h ==null 是无锁状态
        //        也可能是 t == h == head ，第一个获取锁失败的 线程会帮 锁持有线程 补充创建一个 head

        //(s = h.next) == null 成立 说明 可能是 现在判断的时候 刚好有线程 第一个失败，然后自旋入队，在 cas 设置了head后 head 没有设置next 这时也是存在 等待节点 只是还没把自己放进去

        return h != t && ((s = h.next) == null || s.thread != Thread.currentThread());
    }

    private void release(int arg) {
        //释放锁
        if (tryRelease(arg)) {
            //锁已经完全释放了 得让 等待锁的 线程去获取锁
            Node head = this.head;

            //你得知道，有没有等待者？   就是判断 head.next == null  说明没有等待者， head.next != null 说明有等待者..
            if(head.next != null) {
                //公平锁，就是唤醒head.next节点
                unparkSuccessor(head);
            }
        }
    }

    /**
     * 尝试释放锁 完全释放返回true
     * @param arg
     * @return
     */
    private boolean tryRelease(int arg) {
        int c = getState() - arg;

        if (getExclusiveOwnerThread() != Thread.currentThread()) {
            //释放锁线程 不是持有锁线程
            throw new RuntimeException("不是持有锁线程 不允许瞎操作");
        }

        if (c == 0) {
            //说明已经完全 释放了
            this.exclusiveOwnerThread = null;
            this.state = c;
            return true;
        }
        this.state =c;
        return false;
    }

    private void unparkSuccessor(Node node){
        Node s = node.next;
        //唤醒后续一个线程
        if (s!=null && s.thread != null) {
            LockSupport.unpark(s.thread);
        }
    }

    private void setHead(Node node) {
        this.head = node;
        //为什么？ 因为当前node已经是获取锁成功的线程了...
        node.thread = null;
        node.pre = null;
    }

    public int getState() {
        return state;
    }

    public Thread getExclusiveOwnerThread() {
        return exclusiveOwnerThread;
    }

    public Node getHead() {
        return head;
    }

    public Node getTail() {
        return tail;
    }

    private static final Unsafe unsafe;
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);

            stateOffset = unsafe.objectFieldOffset
                    (MiniReentrantLock.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset
                    (MiniReentrantLock.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset
                    (MiniReentrantLock.class.getDeclaredField("tail"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }
}