package cn.lsx.concurrent.proxy;

/**
 * aop cat
 * @author lsx
 * @date 2021/10/31 16:47
 **/
public class Cat implements Animal {

	@Override
	public void eat() {
		System.out.println("吃老鼠");
	}
}
