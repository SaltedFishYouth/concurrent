package cn.lsx.concurrent.proxy.demo04;

public interface MyMethodInterceptor {

    /**
     * 方法拦截器接口 增强逻辑 都写里面
     * @param invocation
     * @return
     */
    Object invoke(MyMethodInvocation invocation) throws Throwable;
}
