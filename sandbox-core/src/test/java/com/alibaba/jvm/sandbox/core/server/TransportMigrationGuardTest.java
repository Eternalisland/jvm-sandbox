package com.alibaba.jvm.sandbox.core.server;

import com.alibaba.jvm.sandbox.core.server.jetty.JettyCoreServer;
import com.alibaba.jvm.sandbox.core.server.netty.NettyCoreServer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Jetty -> Netty 迁移的回归保护。
 *
 * <p>测试意图：安全扫描已经证明 Jetty 运行时依赖会暴露目标应用；因此迁移完成后，
 * sandbox-core 的运行时类路径不应再能加载 Jetty Server，同时 CoreServer 入口必须切换到 Netty。
 * 这两个断言在迁移前均应失败，用于形成明确的 Red -> Green 证据。</p>
 */
class TransportMigrationGuardTest {

    @Test
    void shouldNotExposeAnyRemovedJettyRuntimeOnCoreClasspath() {
        final String[] removedJettyClasses = {
                "org.eclipse.jetty.server.Server",
                "org.eclipse.jetty.websocket.server.JettyWebSocketServerContainer",
                "org.eclipse.jetty.ee10.servlet.ServletContextHandler"
        };
        for (final String className : removedJettyClasses) {
            assertThrows(
                    ClassNotFoundException.class,
                    () -> Class.forName(className, false, getClass().getClassLoader()),
                    () -> "Removed Jetty runtime class is still visible: " + className
            );
        }
    }

    @Test
    void shouldUseNettyCoreServerAsProxyDelegate() throws Exception {
        final Field field = ProxyCoreServer.class.getDeclaredField("classOfCoreServerImpl");
        field.setAccessible(true);
        final Class<?> implementation = (Class<?>) field.get(null);
        assertEquals(
                "com.alibaba.jvm.sandbox.core.server.netty.NettyCoreServer",
                implementation.getName()
        );
    }

    @Test
    @SuppressWarnings("deprecation")
    void shouldKeepLegacyJettyCoreServerEntryPointCompatibleWithoutLoadingJetty() {
        assertSame(NettyCoreServer.getInstance(), JettyCoreServer.getInstance());
    }
}
