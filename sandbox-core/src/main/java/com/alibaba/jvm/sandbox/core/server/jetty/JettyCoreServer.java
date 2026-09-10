package com.alibaba.jvm.sandbox.core.server.jetty;

import com.alibaba.jvm.sandbox.core.server.CoreServer;
import com.alibaba.jvm.sandbox.core.server.netty.NettyCoreServer;

/**
 * @deprecated 仅作为旧内部类名的兼容入口保留。自 2026-09 起实际 HTTP transport 已完全由 Netty 提供，
 *             本类不再引用、加载或依赖任何 org.eclipse.jetty 类。
 */
@Deprecated
public final class JettyCoreServer {

    private JettyCoreServer() {
    }

    /**
     * 兼容历史上直接反射 JettyCoreServer#getInstance() 的调用方。
     *
     * @return Netty CoreServer 单例
     */
    public static CoreServer getInstance() {
        return NettyCoreServer.getInstance();
    }
}
