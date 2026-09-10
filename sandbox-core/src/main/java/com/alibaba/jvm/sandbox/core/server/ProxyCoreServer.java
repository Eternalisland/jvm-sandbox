package com.alibaba.jvm.sandbox.core.server;

import com.alibaba.jvm.sandbox.core.CoreConfigure;
import com.alibaba.jvm.sandbox.core.server.netty.NettyCoreServer;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;

public class ProxyCoreServer implements CoreServer {

    /*
     * 2026-09 transport migration:
     * Keep the CoreServer facade stable and switch only the concrete HTTP transport. This preserves
     * every existing caller of ProxyCoreServer while ensuring Jetty is no longer loaded into target JVMs.
     * TransportMigrationGuardTest verifies this delegate remains Netty and Jetty classes stay absent.
     */
    private final static Class<? extends CoreServer> classOfCoreServerImpl
            = NettyCoreServer.class;

    private final CoreServer proxy;

    private ProxyCoreServer(CoreServer proxy) {
        this.proxy = proxy;
    }


    @Override
    public boolean isBind() {
        return proxy.isBind();
    }

    @Override
    public void unbind() throws IOException {
        proxy.unbind();
    }

    @Override
    public InetSocketAddress getLocal() throws IOException {
        return proxy.getLocal();
    }

    @Override
    public void bind(CoreConfigure cfg, Instrumentation inst) throws IOException {
        proxy.bind(cfg, inst);
    }

    @Override
    public void destroy() {
        proxy.destroy();
    }

    @Override
    public String toString() {
        return "proxy:" + proxy.toString();
    }

    public static CoreServer getInstance() {
        try {
            return new ProxyCoreServer(
                    (CoreServer) classOfCoreServerImpl
                            .getMethod("getInstance")
                            .invoke(null)
            );
        } catch (Throwable cause) {
            throw new RuntimeException(cause);
        }
    }

}
