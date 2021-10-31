package cn.lsx.concurrent.proxy.demo04;

/**
 * 
 * @author lsx
 * @date 2021/10/31 23:07
 **/
public class TwoMethodInterceptor implements MyMethodInterceptor{
    @Override
    public Object invoke(MyMethodInvocation invocation) throws Throwable{
        System.out.println("实验喂食 给食物");
        Object proceed = invocation.proceed();
        System.out.println("实验喂食 查看吃饱没有");
        return proceed;
    }
}
