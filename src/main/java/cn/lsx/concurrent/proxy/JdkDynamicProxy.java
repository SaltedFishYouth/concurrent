package cn.lsx.concurrent.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * @author lsx
 * @date 2021/10/31 16:48
 **/
public class JdkDynamicProxy implements InvocationHandler {
	/**
	 * 被代理对象
	 */
	private Object target;

	public JdkDynamicProxy(Object target) {
		this.target = target;
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
		System.out.println("好饿！");

		Object ret = method.invoke(target, args);

		System.out.println("吃饱了！");

		return ret;
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
