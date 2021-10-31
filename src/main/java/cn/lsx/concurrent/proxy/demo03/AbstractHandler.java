package cn.lsx.concurrent.proxy.demo03;

/**
 * 
 * @author lsx
 * @date 2021/10/31 21:08
 **/
public abstract class AbstractHandler {
    /**
     * 责任链 下一个 处理单元
     */
    private AbstractHandler nextHandler;

    abstract Object invoke(TargetMethod method) throws Throwable;

    public Object proceed(TargetMethod method) throws Throwable{
        if (!hasNext()) {
            return method.getMethod().invoke(method.getTarget(), method.getArgs());
        }

        return nextHandler.invoke(method);
    }
    public boolean hasNext(){
        if (nextHandler != null) {
            return true;
        }
        return false;
    }

    public static class HeadHandler extends AbstractHandler{
        @Override
        Object invoke(TargetMethod method){
            return null;
        }
    }

    public void setNextHandler(AbstractHandler nextHandler) {
        this.nextHandler = nextHandler;
    }
}
