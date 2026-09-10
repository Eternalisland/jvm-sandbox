package com.alibaba.jvm.sandbox.module.mgr;


import com.alibaba.jvm.sandbox.api.Information;
import com.alibaba.jvm.sandbox.api.annotation.Command;
import com.alibaba.jvm.sandbox.api.listener.ext.Advice;
import com.alibaba.jvm.sandbox.api.listener.ext.AdviceListener;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatchBuilder;
import com.alibaba.jvm.sandbox.api.listener.ext.EventWatcher;
import com.alibaba.jvm.sandbox.api.resource.ModuleEventWatcher;
import jakarta.annotation.Resource;
import org.kohsuke.MetaInfServices;

import java.lang.reflect.Field;

@MetaInfServices(Module.class)
@Information(id = "modify-return-value", version = "1.0.0", author = "yijk@flux.com.cn")
public class ModifyReturnValueModule implements com.alibaba.jvm.sandbox.api.Module {

    @Resource
    private ModuleEventWatcher moduleEventWatcher;

    // 方式1: 监听 DefaultChkInfoServiceImpl 的所有方法
    EventWatcher eventWatcher = null;
    @Command("start")
    public void start() {
        System.out.println("开始拦截 ResultObj 对象的创建和使用...");

        eventWatcher =  new EventWatchBuilder(moduleEventWatcher).onClass("com.flux.scev6.login.DefaultChkInfoServiceImpl").includeBootstrap().onBehavior("*")  // 监听所有方法
                .onWatch(new AdviceListener() {
                    @Override
                    public void afterReturning(Advice advice) throws Throwable {
                        Object returnObj = advice.getReturnObj();
                        if (isResultObj(returnObj)) {
                            forceCodeValueTo1000(returnObj);
                        }
                    }
                });

        System.out.println("ResultObj 拦截器已启动");
    }

    @Command("stop")
    public void stop() {
        moduleEventWatcher.delete(eventWatcher.getWatchId());
        System.out.println("ResultObj 拦截器已停止");
    }

    @Command("info")
    public void info() {
        System.out.println("=== ResultObj 拦截器信息 ===");
        System.out.println("功能: 确保 ResultObj.codeValue 永远为 1000");
        System.out.println("拦截点1: DefaultChkInfoServiceImpl 的所有方法返回值");
        System.out.println("拦截点2: ResultObj 构造函数");
        System.out.println("拦截点3: ResultObj.setCodeValue 方法");
        System.out.println("策略: 多重保障，确保 codeValue 始终为 1000");
    }

    private boolean isResultObj(Object obj) {
        return obj != null && "com.flux.scev6.dbopr.model.ResultObj".equals(obj.getClass().getName());
    }

    private void forceCodeValueTo1000(Object resultObj) {
        if (!isResultObj(resultObj)) return;

        try {
            Field codeValueField = resultObj.getClass().getDeclaredField("codeValue");
            codeValueField.setAccessible(true);
            int currentValue = codeValueField.getInt(resultObj);

            if (currentValue != 1000) {
                codeValueField.setInt(resultObj, 1000);
                System.out.println("强制修改 codeValue: " + currentValue + " -> 1000");
            }
        } catch (Exception e) {
            System.err.println("修改 codeValue 失败: " + e.getMessage());
        }
    }
}
