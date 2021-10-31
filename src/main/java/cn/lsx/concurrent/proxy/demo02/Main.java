package cn.lsx.concurrent.proxy.demo02;

import cn.lsx.concurrent.proxy.demo01.Animal;
import cn.lsx.concurrent.proxy.demo01.Dog;
import cn.lsx.concurrent.proxy.demo01.JdkDynamicProxy;

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

        //2、第一次代理
        JdkDynamicProxy dynamicProxy01 = new JdkDynamicProxy(dog);
        //第一次代理
        Animal proxy01 = (Animal )dynamicProxy01.getProxy();

        System.out.println("------------------第一次代理--------------------");
        proxy01.eat();

        //3、第二次代理，需要 JdkDynamicProxy02 + proxy01
        JdkDynamicProxy02 dynamicProxy02 = new JdkDynamicProxy02(proxy01);

        Animal proxy02 = (Animal)dynamicProxy02.getProxy();

        System.out.println("------------------第二次代理--------------------");
        proxy02.eat();

    }
}
