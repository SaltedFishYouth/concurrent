package cn.lsx.concurrent.proxy.demo04;

import cn.lsx.concurrent.proxy.demo03.TargetMethod;

import java.util.List;

/**
 * 
 * @author lsx
 * @date 2021/10/31 23:03
 **/
public class MyMehtodInvocationImpl implements MyMethodInvocation{
    private TargetMethod targetMethod;

    private List<MyMethodInterceptor> interceptors;
    private int index = 0;
    @Override
    public Object proceed() throws Throwable{
        //拦截器 全部执行完毕，接下里需要执行被代理接口方法！
        if (index == interceptors.size()){
            return targetMethod.getMethod().invoke(targetMethod.getTarget(),targetMethod.getArgs());
        }
        //到这里，说明还有拦截器 未执行呢
        MyMethodInterceptor interceptor = interceptors.get(index++);

        return interceptor.invoke(this);
    }

    public MyMehtodInvocationImpl(TargetMethod targetMethod, List<MyMethodInterceptor> interceptors) {
        this.targetMethod = targetMethod;
        this.interceptors = interceptors;
    }
}
