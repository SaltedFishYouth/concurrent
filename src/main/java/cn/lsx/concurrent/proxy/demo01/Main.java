package cn.lsx.concurrent.proxy.demo01;

import sun.misc.ProxyGenerator;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * AOP main
 *
 * @author lsx
 * @date 2021/10/31 16:53
 **/
public class Main {
    public static void main(String[] args) {
        //1、创建被代理目标
        Dog dog = new Dog();
        dog.eat();
        System.out.println("--------------------------------");
        //2、创建JdkDynamicProxy
        JdkDynamicProxy dynamicProxy = new JdkDynamicProxy(dog);

        //3、获取代理之后的对象
        Animal proxy = (Animal) dynamicProxy.getProxy();
        proxy.eat();

        Main.generateClassFile(proxy.getClass(),"&proxyDemo");
    }

    /**
     * 代理类 生成的字节码文件
     * @param clazz
     * @param proxyName
     */
    public static void generateClassFile(Class clazz, String proxyName) {
        //根据类信息和提供的代理类名称生成字节码
        byte[] bytes = ProxyGenerator.generateProxyClass(proxyName, clazz.getInterfaces());
        FileOutputStream out = null;
        try {
            out = new FileOutputStream("E:\\workspace\\concurrent\\src\\main\\java\\cn\\lsx\\concurrent\\proxy\\demo01\\" + proxyName + ".class");
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
