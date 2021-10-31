package cn.lsx.concurrent.proxy.demo04;

import cn.lsx.concurrent.proxy.demo01.Animal;
import cn.lsx.concurrent.proxy.demo01.Dog;
import cn.lsx.concurrent.proxy.demo03.AbstractHandler;
import cn.lsx.concurrent.proxy.demo03.JdkDynamicProxy03;
import cn.lsx.concurrent.proxy.demo03.TargetMethod;

/**
 * 多重代理
 * @author lsx
 * @date 2021/10/31 20:57
 **/
public class Main {
    public static void main(String[] args) {
        //1、创建被代理对象
        Dog dog = new Dog();

        System.out.println("-----------------没代理---------------------");
        dog.eat();

        //2、先创建 jdkDynamicproxy 对象
        JdkDynamicProxy04 dynamicProxy = new JdkDynamicProxy04(dog);

        //3、添加拦截器增强
        dynamicProxy.addInterceptor(new OneMethodInterceptor());
        dynamicProxy.addInterceptor(new TwoMethodInterceptor());

        //4、获取代理对象
        Animal proxy = (Animal)dynamicProxy.getProxy();

        System.out.println("-----------------多重代理---------------------");
        proxy.eat();
    }
}


