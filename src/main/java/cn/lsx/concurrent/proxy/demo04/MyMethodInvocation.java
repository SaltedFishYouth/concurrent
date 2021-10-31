package cn.lsx.concurrent.proxy.demo04;

public interface MyMethodInvocation {
    /**
     * 驱动拦截器链 执行增强逻辑 被代理方法调用
     * @return
     */
    Object proceed() throws Throwable;


}
