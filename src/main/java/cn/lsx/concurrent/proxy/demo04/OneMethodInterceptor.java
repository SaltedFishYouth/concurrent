package cn.lsx.concurrent.proxy.demo04;

/**
 * 
 * @author lsx
 * @date 2021/10/31 23:07
 **/
public class OneMethodInterceptor implements MyMethodInterceptor{
    @Override
    public Object invoke(MyMethodInvocation invocation) throws Throwable{
        System.out.println("实验观察 摇铃");
        Object proceed = invocation.proceed();
        System.out.println("实验观察 查看反射情况");
        return proceed;
    }
}
