/*
 * Copyright (c) 1997, 2013, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;
import java.lang.ref.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This class provides thread-local variables.  These variables differ from
 * their normal counterparts in that each thread that accesses one (via its
 * {@code get} or {@code set} method) has its own, independently initialized
 * copy of the variable.  {@code ThreadLocal} instances are typically private
 * static fields in classes that wish to associate state with a thread (e.g.,
 * a user ID or Transaction ID).
 *
 * <p>For example, the class below generates unique identifiers local to each
 * thread.
 * A thread's id is assigned the first time it invokes {@code ThreadId.get()}
 * and remains unchanged on subsequent calls.
 * <pre>
 * import java.util.concurrent.atomic.AtomicInteger;
 *
 * public class ThreadId {
 *     // Atomic integer containing the next thread ID to be assigned
 *     private static final AtomicInteger nextId = new AtomicInteger(0);
 *
 *     // Thread local variable containing each thread's ID
 *     private static final ThreadLocal&lt;Integer&gt; threadId =
 *         new ThreadLocal&lt;Integer&gt;() {
 *             &#64;Override protected Integer initialValue() {
 *                 return nextId.getAndIncrement();
 *         }
 *     };
 *
 *     // Returns the current thread's unique ID, assigning it if necessary
 *     public static int get() {
 *         return threadId.get();
 *     }
 * }
 * </pre>
 * <p>Each thread holds an implicit reference to its copy of a thread-local
 * variable as long as the thread is alive and the {@code ThreadLocal}
 * instance is accessible; after a thread goes away, all of its copies of
 * thread-local instances are subject to garbage collection (unless other
 * references to these copies exist).
 *
 * @author  Josh Bloch and Doug Lea
 * @since   1.2
 */
public class ThreadLocal<T> {
    /**
     * ThreadLocals rely on per-thread linear-probe hash maps attached
     * to each thread (Thread.threadLocals and
     * inheritableThreadLocals).  The ThreadLocal objects act as keys,
     * searched via threadLocalHashCode.  This is a custom hash code
     * (useful only within ThreadLocalMaps) that eliminates collisions
     * in the common case where consecutively constructed ThreadLocals
     * are used by the same threads, while remaining well-behaved in
     * less common cases.
     */
    //使用 threadLocalHashCode & (table.length -1) 的位置 作为 entry(threadLocal对象,当前线程生成的value)存放的位置
    //table 是threadLocals 每个线程 会创建一个
    private final int threadLocalHashCode = nextHashCode();

    /**
     * The next hash code to be given out. Updated atomically. Starts at
     * zero.
     * 创建 threadLocal 对象时 使用nextHashCode 分配一个 hash值给这个对象
     */
    private static AtomicInteger nextHashCode =
        new AtomicInteger();

    /**
     * The difference between successively generated hash codes - turns
     * implicit sequential thread-local IDs into near-optimally spread
     * multiplicative hash values for power-of-two-sized tables.
     * 每创建一个 threadLocal 对象 这个HASH_INCREMENT 这个值就会增长
     * 这个值是 斐波那契数 黄金分割数 创建的hash分布比较均匀
     */
    private static final int HASH_INCREMENT = 0x61c88647;

    /**
     * Returns the next hash code.
     * 创建新的threadLocal对象时， 给当前对象分配一个hash
     */
    private static int nextHashCode() {
        return nextHashCode.getAndAdd(HASH_INCREMENT);
    }

    /**
     * Returns the current thread's "initial value" for this
     * thread-local variable.  This method will be invoked the first
     * time a thread accesses the variable with the {@link #get}
     * method, unless the thread previously invoked the {@link #set}
     * method, in which case the {@code initialValue} method will not
     * be invoked for the thread.  Normally, this method is invoked at
     * most once per thread, but it may be invoked again in case of
     * subsequent invocations of {@link #remove} followed by {@link #get}.
     *
     * <p>This implementation simply returns {@code null}; if the
     * programmer desires thread-local variables to have an initial
     * value other than {@code null}, {@code ThreadLocal} must be
     * subclassed, and this method overridden.  Typically, an
     * anonymous inner class will be used.
     *
     * 默认返回null 一般需要重新的
     * @return the initial value for this thread-local
     */
    protected T initialValue() {
        return null;
    }

    /**
     * Creates a thread local variable. The initial value of the variable is
     * determined by invoking the {@code get} method on the {@code Supplier}.
     *
     * @param <S> the type of the thread local's value
     * @param supplier the supplier to be used to determine the initial value
     * @return a new thread local variable
     * @throws NullPointerException if the specified supplier is null
     * @since 1.8
     */
    public static <S> ThreadLocal<S> withInitial(Supplier<? extends S> supplier) {
        return new SuppliedThreadLocal<>(supplier);
    }

    /**
     * Creates a thread local variable.
     * @see #withInitial(java.util.function.Supplier)
     */
    public ThreadLocal() {
    }

    /**
     * Returns the value in the current thread's copy of this
     * thread-local variable.  If the variable has no value for the
     * current thread, it is first initialized to the value returned
     * by an invocation of the {@link #initialValue} method.
     * 返回当前线程与当前threadLocal 对象关联的 线程局部变量 只有当前线程能访问道
     * @return the current thread's value of this thread-local
     */
    public T get() {
        //获取当前线程
        Thread t = Thread.currentThread();
        //获取当前线程对应的 threadLocalMap
        ThreadLocalMap map = getMap(t);
        //map != null 条件成立 说明已经拥有自己的threadLocalMao对象了
        if (map != null) {
            //根据 当前threadLocal 对象获取 threadLocalMap 中该 threadLocal关联的entry
            ThreadLocalMap.Entry e = map.getEntry(this);
            //条件成立 说明当前线程初始化过 与当前蓄电池threadLocal对象关联的 线程局部变量
            if (e != null) {
                @SuppressWarnings("unchecked")
                T result = (T)e.value;
                //返回value
                return result;
            }
        }
        //情况1：ThreadLocalMap 是 null
        //情况2：与当前线程 与threadLocal 对象没有 生成相关联的 局部变量

        //setInitialValue 初始化当前线程 与当前threadLoc相关联的value 如果没map
        // 还会创建map
        return setInitialValue();
    }

    /**
     * Variant of set() to establish initialValue. Used instead
     * of set() in case user has overridden the set() method.
     *
     * setInitialValue 初始化当前线程 与当前threadLoc相关联的value 如果没map
     * 还会创建map
     * @return the initial value
     */
    private T setInitialValue() {
        //调用当前threadLocal对象的initialvalue 方法 （一般都会重写）
        T value = initialValue();
        //获取当前线程
        Thread t = Thread.currentThread();
        //获取当前线程对应的 ThreadLocalMap
        ThreadLocalMap map = getMap(t);
        //条件成立 已经初始化了 ThreadLocalMap 直接插入 当前线程对应threadLocal对象的value
        if (map != null)
            map.set(this, value);
        else
            //map 未初始化 创建当前线程的map 并设置 key为当前threadLocal对象 value
            createMap(t, value);
        return value;
    }

    /**
     * Sets the current thread's copy of this thread-local variable
     * to the specified value.  Most subclasses will have no need to
     * override this method, relying solely on the {@link #initialValue}
     * method to set the values of thread-locals.
     * 修改当前线程 与当前threadLocal对象 关联的局部变量
     * @param value the value to be stored in the current thread's copy of
     *        this thread-local.
     */
    public void set(T value) {
        //获取当前线程
        Thread t = Thread.currentThread();
        //获取当前线程 对应 当前线程的 threadLocalMap
        ThreadLocalMap map = getMap(t);
        //条件 成立说明 map 已经初始化 直接 修改
        if (map != null)
            //已当前threadLocal对象为key 修改value
            map.set(this, value);
        else
            //说明 map 未初始化 初始化创建
            createMap(t, value);
    }

    /**
     * Removes the current thread's value for this thread-local
     * variable.  If this thread-local variable is subsequently
     * {@linkplain #get read} by the current thread, its value will be
     * reinitialized by invoking its {@link #initialValue} method,
     * unless its value is {@linkplain #set set} by the current thread
     * in the interim.  This may result in multiple invocations of the
     * {@code initialValue} method in the current thread.
     *
     * @since 1.5
     */
     public void remove() {
         ThreadLocalMap m = getMap(Thread.currentThread());
         if (m != null)
             m.remove(this);
     }

    /**
     * Get the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param  t the current thread
     * @return the map
     */
    ThreadLocalMap getMap(Thread t) {
        //返回当前线程的 threadLocals
        return t.threadLocals;
    }

    /**
     * Create the map associated with a ThreadLocal. Overridden in
     * InheritableThreadLocal.
     *
     * @param t the current thread
     * @param firstValue value for the initial entry of the map
     */
    void createMap(Thread t, T firstValue) {
        t.threadLocals = new ThreadLocalMap(this, firstValue);
    }

    /**
     * Factory method to create map of inherited thread locals.
     * Designed to be called only from Thread constructor.
     *
     * @param  parentMap the map associated with parent thread
     * @return a map containing the parent's inheritable bindings
     */
    static ThreadLocalMap createInheritedMap(ThreadLocalMap parentMap) {
        return new ThreadLocalMap(parentMap);
    }

    /**
     * Method childValue is visibly defined in subclass
     * InheritableThreadLocal, but is internally defined here for the
     * sake of providing createInheritedMap factory method without
     * needing to subclass the map class in InheritableThreadLocal.
     * This technique is preferable to the alternative of embedding
     * instanceof tests in methods.
     */
    T childValue(T parentValue) {
        throw new UnsupportedOperationException();
    }

    /**
     * An extension of ThreadLocal that obtains its initial value from
     * the specified {@code Supplier}.
     */
    static final class SuppliedThreadLocal<T> extends ThreadLocal<T> {

        private final Supplier<? extends T> supplier;

        SuppliedThreadLocal(Supplier<? extends T> supplier) {
            this.supplier = Objects.requireNonNull(supplier);
        }

        @Override
        protected T initialValue() {
            return supplier.get();
        }
    }

    /**
     * ThreadLocalMap is a customized hash map suitable only for
     * maintaining thread local values. No operations are exported
     * outside of the ThreadLocal class. The class is package private to
     * allow declaration of fields in class Thread.  To help deal with
     * very large and long-lived usages, the hash table entries use
     * WeakReferences for keys. However, since reference queues are not
     * used, stale entries are guaranteed to be removed only when
     * the table starts running out of space.
     */
    static class ThreadLocalMap {

        /**
         * The entries in this hash map extend WeakReference, using
         * its main ref field as the key (which is always a
         * ThreadLocal object).  Note that null keys (i.e. entry.get()
         * == null) mean that the key is no longer referenced, so the
         * entry can be expunged from table.  Such entries are referred to
         * as "stale entries" in the code that follows.
         * Entry 是个弱引用
         * a = null;
         * 下一次 GC 对象就被回收 不管是否有 弱引用关联这个对象
         * key 使用的是 弱引用 保存 ThreadLocal 对象
         * value 使用强引用 保存 ThreadLocal对象与当前线程关联的 value
         *
         * 当ThreadLocal 对象被回收后，ThreadLocalMap中关联的 entry 获取的就是null
         * 可以区分出那些entry 是过期
         */
        static class Entry extends WeakReference<ThreadLocal<?>> {
            /** The value associated with this ThreadLocal. */
            Object value;

            Entry(ThreadLocal<?> k, Object v) {
                super(k);
                value = v;
            }
        }

        /**
         * The initial capacity -- MUST be a power of two.
         * 散列表 初始长度 长度是2的次方数
         */
        private static final int INITIAL_CAPACITY = 16;

        /**
         * The table, resized as necessary.
         * table.length MUST always be a power of two.
         * 散列表数组引用
         */
        private Entry[] table;

        /**
         * The number of entries in the table.
         * 散列表占用情况
         */
        private int size = 0;

        /**
         * The next size value at which to resize.
         * 扩容触发阈值 len *2/3
         * 触发后 调用 rehash ()
         */
        private int threshold; // Default to 0

        /**
         * Set the resize threshold to maintain at worst a 2/3 load factor.
         * 将阈值设置为（当前数组长度*2）/3
         */
        private void setThreshold(int len) {
            threshold = len * 2 / 3;
        }

        /**
         * Increment i modulo len.
         * i :当前下标
         * len:当前数组长度
         */
        private static int nextIndex(int i, int len) {
            //当前下标+1 小于 长度 返回+1的值，否则返回头
            //形成环绕访问
            return ((i + 1 < len) ? i + 1 : 0);
        }

        /**
         * Decrement i modulo len.
         * i :当前下标
         * len:当前数组长度
         */
        private static int prevIndex(int i, int len) {
            //当前下标-1 大于0 长度 返回11的值，否则返回末尾值
            //形成环绕访问
            return ((i - 1 >= 0) ? i - 1 : len - 1);
        }

        /**
         * Construct a new map initially containing (firstKey, firstValue).
         * ThreadLocalMaps are constructed lazily, so we only create
         * one when we have at least one entry to put in it.
         * 延迟创建
         * thread 创建时不会创建，只有当 线程第一次 set 或get 时才会 创建threadLocalMap对象
         * firstKey：threadLocal对象
         * firstValue:当前线程 对应 threadLocal的 value
         */
        ThreadLocalMap(ThreadLocal<?> firstKey, Object firstValue) {
            //初始化数组
            table = new Entry[INITIAL_CAPACITY];
            //寻址算法 计算 当前threadLocal 对象对应的hash 值
            // 2的次方数-1 每个位 都是1 与任何数 做&运算一定是小于 这个都是1的数 即 16 对应的15 寻址获取到一定是小于15的数
            int i = firstKey.threadLocalHashCode & (INITIAL_CAPACITY - 1);
            //创建Entry 对象 key 为threadLocal 并赋值到 对应hash 下标位置
            table[i] = new Entry(firstKey, firstValue);
            //刚创建 已使用的 是1
            size = 1;
            //设置 库容阈值
            setThreshold(INITIAL_CAPACITY);
        }

        /**
         * Construct a new map including all Inheritable ThreadLocals
         * from given parent map. Called only by createInheritedMap.
         *
         * @param parentMap the map associated with parent thread.
         */
        private ThreadLocalMap(ThreadLocalMap parentMap) {
            Entry[] parentTable = parentMap.table;
            int len = parentTable.length;
            setThreshold(len);
            table = new Entry[len];

            for (int j = 0; j < len; j++) {
                Entry e = parentTable[j];
                if (e != null) {
                    @SuppressWarnings("unchecked")
                    ThreadLocal<Object> key = (ThreadLocal<Object>) e.get();
                    if (key != null) {
                        Object value = key.childValue(e.value);
                        Entry c = new Entry(key, value);
                        int h = key.threadLocalHashCode & (len - 1);
                        while (table[h] != null)
                            h = nextIndex(h, len);
                        table[h] = c;
                        size++;
                    }
                }
            }
        }

        /**
         * Get the entry associated with key.  This method
         * itself handles only the fast path: a direct hit of existing
         * key. It otherwise relays to getEntryAfterMiss.  This is
         * designed to maximize performance for direct hits, in part
         * by making this method readily inlinable.
         *
         * @param  key the thread local object 某个 threadLocal 对象
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntry(ThreadLocal<?> key) {
            //寻址算法
            int i = key.threadLocalHashCode & (table.length - 1);
            //获取到Entry 对象
            Entry e = table[i];
            //Entry 已经初始化  且key相等 返回Entry
            if (e != null && e.get() == key)
                return e;
            else
                //情况1：e ==null
                //情况2：e.key != key
                // getEntryAfterMiss 方法继续向当前桶位后面继续搜索 key相等的
                // 因为 存储时 发生冲突 没有 形成链表 而是直接找到 后面一个可用的桶位放进去
                return getEntryAfterMiss(key, i, e);
        }

        /**
         * Version of getEntry method for use when key is not found in
         * its direct hash slot.
         * 向后查询 key 相等的 桶位
         * @param  key the thread local object threadLocal 对象
         * @param  i the table index for key's hash code key 计算出来的下标
         * @param  e the entry at table[i] 上面获取到 key 不相等的 entry
         * @return the entry associated with key, or null if no such
         */
        private Entry getEntryAfterMiss(ThreadLocal<?> key, int i, Entry e) {
            //获取 散列表
            Entry[] tab = table;
            int len = tab.length;
            //循环处理 元素 不能为null
            while (e != null) {
                //获取当前桶位 的key
                ThreadLocal<?> k = e.get();
                //key相等找到了 直接返回
                if (k == key)
                    return e;
                //k == null ：key 是弱引用 threadLocal 被回收了
                if (k == null)
                    //做一次探测过期数据回收
                    expungeStaleEntry(i);
                else
                    //更新index 继续搜索
                    i = nextIndex(i, len);
                //获取下一个元素
                e = tab[i];
            }
            return null;
        }

        /**
         * Set the value associated with key.
         *
         * @param key the thread local object
         * @param value the value to be set
         */
        private void set(ThreadLocal<?> key, Object value) {

            // We don't use a fast path as with get() because it is at
            // least as common to use set() to create new entries as
            // it is to replace existing ones, in which case, a fast
            // path would fail more often than not.

            //获取threadLocalMap 维护的 table
            Entry[] tab = table;
            int len = tab.length;
            //获取当前threadLocal 对象 对应 在threadLocalMap 的hash 值
            int i = key.threadLocalHashCode & (len-1);

            //循环向下找可用slot
            //情况1：k==key
            //情况2：k==null 过期数据 占用它
            //情况3：slot==null 未使用桶位
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                //获取当前元素的key
                ThreadLocal<?> k = e.get();
                //寻找到 已存在 做替换操作
                if (k == key) {
                    e.value = value;
                    return;
                }
                //说明向下寻找时 遇到了 key 被回收的情况了 是过期数据
                if (k == null) {
                    replaceStaleEntry(key, value, i);
                    return;
                }
            }
            //走到这 说明 没有创建过 对应的 key  直接创建新元素
            tab[i] = new Entry(key, value);
            int sz = ++size;
            //做一次启发式清理 条件成立说明 启发式清理未清理到数据
            //达到扩容阈值
            if (!cleanSomeSlots(i, sz) && sz >= threshold)
                rehash();
        }

        /**
         * Remove the entry for key.
         */
        private void remove(ThreadLocal<?> key) {
            Entry[] tab = table;
            int len = tab.length;
            int i = key.threadLocalHashCode & (len-1);
            for (Entry e = tab[i];
                 e != null;
                 e = tab[i = nextIndex(i, len)]) {
                if (e.get() == key) {
                    e.clear();
                    expungeStaleEntry(i);
                    return;
                }
            }
        }

        /**
         * Replace a stale entry encountered during a set operation
         * with an entry for the specified key.  The value passed in
         * the value parameter is stored in the entry, whether or not
         * an entry already exists for the specified key.
         *
         * As a side effect, this method expunges all stale entries in the
         * "run" containing the stale entry.  (A run is a sequence of entries
         * between two null slots.)
         * 替换过期Entry
         * @param  key the key
         * @param  value the value to be associated with key
         * @param  staleSlot index of the first stale entry encountered while
         *         searching for key.
         */
        private void replaceStaleEntry(ThreadLocal<?> key, Object value,
                                       int staleSlot) {
            //获取散列表
            Entry[] tab = table;
            int len = tab.length;
            //遍历的元素
            Entry e;

            // Back up to check for prior stale entry in current run.
            // We clean out whole runs at a time to avoid continual
            // incremental rehashing due to garbage collector freeing
            // up refs in bunches (i.e., whenever the collector runs).

            //表示 开始探测式清理过期数据的 下标，默认从staleSlot 开始
            int slotToExpunge = staleSlot;

            //以当前的staleSlot 开始向前迭代 查询有没有过期 的数据 直到碰到null
            for (int i = prevIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = prevIndex(i, len)) {
                if (e.get() == null)
                    slotToExpunge = i;
            }
            // Find either the key or trailing null slot of run, whichever
            // occurs first
            //以staleSlot 向后查找 碰到null位置
            for (int i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();

                // If we find key, then we need to swap it
                // with the stale entry to maintain hash table order.
                // The newly stale slot, or any other stale slot
                // encountered above it, can then be sent to expungeStaleEntry
                // to remove or rehash all of the other entries in run.

                //条件成立 说明是替换逻辑
                if (k == key) {
                    e.value = value;
                    //staleSlot , i
                    //将我们要找的entry与过期数据 交换位置 因为staleSlot 一定小于 i 相当于位置做了优化
                    tab[i] = tab[staleSlot];
                    tab[staleSlot] = e;

                    // Start expunge at preceding stale entry if it exists
                    //说明向前查找过期 没找到 向后也未找到过期数据
                    if (slotToExpunge == staleSlot)
                        //修改 开始探测清理过期数据的下标 为当前循环的index
                        slotToExpunge = i;
                    //启发式清理
                    cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
                    return;
                }

                // If we didn't find stale entry on backward scan, the
                // first stale entry seen while scanning for key is the
                // first still present in the run.
                //当前遍历entry 是过期数据
                // slotToExpunge == staleSlot说明向前查找过期 没找到
                if (k == null && slotToExpunge == staleSlot)
                    //向后查询过期数据了 向前又没找到 所以 更新slotToExpunge为当前位置
                    slotToExpunge = i;
            }

            //向后查找中也未找到 k=key 的entry 所以 执行添加逻辑
            // If key not found, put new entry in stale slot
            //添加逻辑
            tab[staleSlot].value = null;
            tab[staleSlot] = new Entry(key, value);

            // If there are any other stale entries in run, expunge them
            //slotToExpunge != staleSlot 说明 除了当前staleSlot 还有其他的过期slot所以开启清理数据逻辑
            if (slotToExpunge != staleSlot)
                //启发式清理
                cleanSomeSlots(expungeStaleEntry(slotToExpunge), len);
        }

        /**
         * Expunge a stale entry by rehashing any possibly colliding entries
         * lying between staleSlot and the next null slot.  This also expunges
         * any other stale entries encountered before the trailing null.  See
         * Knuth, Section 6.4
         * 探测式清理
         * table[staleSlot] 就是过期数据 向后查找 过期数据 直到 null
         * @param staleSlot index of slot known to have null key
         * @return the index of the next null slot after staleSlot
         * (all between staleSlot and this slot will have been checked
         * for expunging).
         */
        private int expungeStaleEntry(int staleSlot) {
            //获取散列表
            Entry[] tab = table;
            int len = tab.length;

            // expunge entry at staleSlot
            //当前 staleSlot 是过期数据 处理过期数据
            tab[staleSlot].value = null;
            tab[staleSlot] = null;
            size--;

            // Rehash until we encounter null
            //e:当前遍历节点 i当前遍历下标
            Entry e;
            int i;
            //for 循环 从 staleSlot+1 开始搜索 过期数据直到 桶位 为null结束
            for (i = nextIndex(staleSlot, len);
                 (e = tab[i]) != null;
                 i = nextIndex(i, len)) {
                ThreadLocal<?> k = e.get();
                //key 被回收 清理数据
                if (k == null) {
                    e.value = null;
                    tab[i] = null;
                    size--;
                } else {
                    //计算当前 key 的hash
                    int h = k.threadLocalHashCode & (len - 1);
                    //与获取到的桶位下标 不一致 说明 插入时冲突了 重新优化位置让 位置更靠近 正确位置 查询效率更高
                    if (h != i) {
                        //将原来的桶位清理
                        tab[i] = null;

                        // Unlike Knuth 6.4 Algorithm R, we must scan until
                        // null because multiple entries could have been stale.
                        //从hash 位置 开始向后寻找 可用桶位 如果hash
                        while (tab[h] != null)
                            h = nextIndex(h, len);
                        tab[h] = e;
                    }
                }
            }
            return i;
        }

        /**
         * Heuristically scan some cells looking for stale entries.
         * This is invoked when either a new element is added, or
         * another stale one has been expunged. It performs a
         * logarithmic number of scans, as a balance between no
         * scanning (fast but retains garbage) and a number of scans
         * proportional to number of elements, that would find all
         * garbage but would cause some insertions to take O(n) time.
         *
         * @param i a position known NOT to hold a stale entry. The
         * scan starts at the element after i.
         *
         * @param n scan control: {@code log2(n)} cells are scanned,
         * unless a stale entry is found, in which case
         * {@code log2(table.length)-1} additional cells are scanned.
         * When called from insertions, this parameter is the number
         * of elements, but when from replaceStaleEntry, it is the
         * table length. (Note: all this could be changed to be either
         * more or less aggressive by weighting n instead of just
         * using straight log n. But this version is simple, fast, and
         * seems to work well.)
         * i：启发式清理开始的位置
         * n:结束的位置
         * @return true if any stale entries have been removed.
         */
        private boolean cleanSomeSlots(int i, int n) {
            //是否清除过 过期数据
            boolean removed = false;
            //获取散列表
            Entry[] tab = table;
            int len = tab.length;
            do {
                //获取当前i的下一个坐标 因为 expungeStaleEntry 返回的一定是null
                i = nextIndex(i, len);
                //获取 table 下标为i的元素
                Entry e = tab[i];

                //当前entry 是过期数据
                if (e != null && e.get() == null) {
                    //更新n为 table 长度
                    n = len;
                    //表示清理过数据
                    removed = true;
                     //以i为开始节点 做一次探测式清理
                    i = expungeStaleEntry(i);
                }
                //假设 table 长度为 16
                //16 =>8 =>4 =>2 =>1 =>0 循环5次
            } while ( (n >>>= 1) != 0);
            return removed;
        }

        /**
         * Re-pack and/or re-size the table. First scan the entire
         * table removing stale entries. If this doesn't sufficiently
         * shrink the size of the table, double the table size.
         */
        private void rehash() {
            //每个桶位调用一次 探测式清理 会把table 中所有过期数据清理掉
            expungeStaleEntries();

            // Use lower threshold for doubling to avoid hysteresis
            //条件成立 entry 数量仍然达到了 threshold*3/4 真正触发扩容
            if (size >= threshold - threshold / 4)
                resize();
        }

        /**
         * Double the capacity of the table.
         */
        private void resize() {
            //获取当前散列表
            Entry[] oldTab = table;
            int oldLen = oldTab.length;
            int newLen = oldLen * 2;
            //创建新的 table 大小 是原来的两倍
            Entry[] newTab = new Entry[newLen];
            int count = 0;
            //遍历老表迁移数据
            for (int j = 0; j < oldLen; ++j) {
                Entry e = oldTab[j];
                if (e != null) {
                    ThreadLocal<?> k = e.get();
                    //过期数据抛弃不迁移
                    if (k == null) {
                        e.value = null; // Help the GC
                    } else {
                        //有用数据，重新计算hash
                        int h = k.threadLocalHashCode & (newLen - 1);
                        //最佳位置 被使用了 往下走找到可用位置
                        while (newTab[h] != null)
                            h = nextIndex(h, newLen);
                        newTab[h] = e;
                        count++;
                    }
                }
            }

            setThreshold(newLen);
            size = count;
            table = newTab;
        }

        /**
         * Expunge all stale entries in the table.
         */
        private void expungeStaleEntries() {
            Entry[] tab = table;
            int len = tab.length;
            for (int j = 0; j < len; j++) {
                Entry e = tab[j];
                if (e != null && e.get() == null)
                    expungeStaleEntry(j);
            }
        }
    }
}
