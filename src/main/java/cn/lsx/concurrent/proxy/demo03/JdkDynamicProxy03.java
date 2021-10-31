package cn.lsx.concurrent.proxy.demo03;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * @author lsx
 * @date 2021/10/31 16:48
 **/
public class JdkDynamicProxy03 implements InvocationHandler {
	/**
	 * 被代理对象
	 */
	private Object target;

	/**
	 * 责任链 头结点
	 */
	private AbstractHandler headHandler;

	public JdkDynamicProxy03(Object target, AbstractHandler headHandler) {
		this.target = target;
		this.headHandler = headHandler;
	}

	/**
	 * @param proxy  代理对象，代理了 target  代理对象内部持有了 target 对象
	 * @param method 被代理对象的方法
	 * @param args   被代理对象的方法入参
	 * @return
	 * @throws Throwable
	 */
	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		TargetMethod targetMethod = new TargetMethod();
		targetMethod.setTarget(target);
		targetMethod.setMethod(method);
		targetMethod.setArgs(args);
		return headHandler.proceed(targetMethod);
	}

	/**
	 * 获取代理后的对象
	 *
	 * @return
	 */
	public Object getProxy() {
		//参数一：类加载器
		//参数二：代理类 需要 实现的接口集合
		//参数三：代理类虽然全部实现了 接口方法，但是接口方法要 依靠InvocationHandler 去处理。
		return Proxy.newProxyInstance(target.getClass().getClassLoader(), target
				.getClass().getInterfaces(), this);
	}
}
