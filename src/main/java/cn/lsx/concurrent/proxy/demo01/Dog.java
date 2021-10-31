package cn.lsx.concurrent.proxy.demo01;

/**
 * aop cat
 * @author lsx
 * @date 2021/10/31 16:47
 **/
public class Dog implements Animal {

	@Override
	public void eat() {
		System.out.println("狗狗吃");
	}
}
