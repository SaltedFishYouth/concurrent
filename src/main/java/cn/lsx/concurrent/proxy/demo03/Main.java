package cn.lsx.concurrent.proxy.demo03;

import cn.lsx.concurrent.proxy.demo01.Animal;
import cn.lsx.concurrent.proxy.demo01.Dog;

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

        //2、创建责任链
        AbstractHandler headHandler = new AbstractHandler.HeadHandler();
        OneHandler oneHandler = new OneHandler();
        oneHandler.setNextHandler(new TwoHandler());
        headHandler.setNextHandler(oneHandler);

        //3、先创建 jdkDynamicproxy 对象
        JdkDynamicProxy03 dynamicProxy = new JdkDynamicProxy03(dog,headHandler);

        //4、获取代理对象
        Animal proxy = (Animal)dynamicProxy.getProxy();

        System.out.println("-----------------多重代理---------------------");
        proxy.eat();
    }

    public static class OneHandler extends AbstractHandler {

        @Override
        Object invoke(TargetMethod method) throws Throwable {
            System.out.println("实验观察者 先摇铃");
            Object result = proceed(method);
            System.out.println("实验观察者  观察动物反射情况");
            return result;
        }
    }

    public static class TwoHandler extends AbstractHandler {

        @Override
        Object invoke(TargetMethod method) throws Throwable {
            System.out.println("实验喂食者 给食物");
            Object result = proceed(method);
            System.out.println("实验喂食者 发现动物吃饱了");
            return result;
        }
    }
}

