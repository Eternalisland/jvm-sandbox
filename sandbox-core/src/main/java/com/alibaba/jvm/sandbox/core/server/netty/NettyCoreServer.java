package com.alibaba.jvm.sandbox.core.server.netty;

import com.alibaba.jvm.sandbox.core.CoreConfigure;
import com.alibaba.jvm.sandbox.core.JvmSandbox;
import com.alibaba.jvm.sandbox.core.server.CoreServer;
import com.alibaba.jvm.sandbox.core.util.Initializer;
import com.alibaba.jvm.sandbox.core.util.LogbackUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;

import static com.alibaba.jvm.sandbox.core.util.NetworkUtils.isPortInUsing;
import static java.lang.String.format;

/**
 * Netty 实现的 Sandbox 控制面 HTTP Server。
 *
 * <p>迁移说明（2026-09）：原实现通过嵌入 Jetty 提供 /sandbox/{namespace}/module/http/*。
 * 安全扫描会把 Jetty 的运行时 CVE 一并暴露到被 attach 的业务 JVM。这里仅替换网络传输层，
 * CoreServer 生命周期、URL、模块加载时机以及 Servlet 参数兼容层保持不变。</p>
 *
 * <p>验证依据：TransportMigrationGuardTest 保证 Jetty 不再出现在运行时类路径；
 * NettyModuleHttpHandlerTest 覆盖原有 Command/Http 路由、查询参数、Servlet 请求/响应参数和状态码。</p>
 */
public class NettyCoreServer implements CoreServer {

    /**
     * Sandbox 控制接口通常是小报文。保留一个较宽松但有上限的聚合阈值，避免恶意请求无限占用目标 JVM 内存。
     */
    static final int MAX_HTTP_CONTENT_LENGTH = 16 * 1024 * 1024;

    private static volatile CoreServer coreServer;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Initializer initializer = new Initializer(true);

    private volatile Channel serverChannel;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup moduleExecutorGroup;
    private CoreConfigure cfg;
    private JvmSandbox jvmSandbox;

    public static CoreServer getInstance() {
        if (null == coreServer) {
            synchronized (CoreServer.class) {
                if (null == coreServer) {
                    coreServer = new NettyCoreServer();
                }
            }
        }
        return coreServer;
    }

    @Override
    public boolean isBind() {
        return initializer.isInitialized();
    }

    @Override
    public synchronized void unbind() throws IOException {
        try {
            initializer.destroyProcess(() -> {
                logger.info("{} is stopping", NettyCoreServer.this);
                shutdownNettyResources();
            });
        } catch (Throwable cause) {
            logger.warn("{} unBind failed.", this, cause);
            throw new IOException("unBind failed.", cause);
        }
    }

    @Override
    public InetSocketAddress getLocal() throws IOException {
        final Channel channel = serverChannel;
        if (!isBind() || null == channel || !(channel.localAddress() instanceof InetSocketAddress)) {
            throw new IOException("server was not bind yet.");
        }
        return (InetSocketAddress) channel.localAddress();
    }

    private void initHttpServer() throws InterruptedException {
        final String serverIp = cfg.getServerIp();
        final int serverPort = cfg.getServerPort();

        // 保留旧 JettyCoreServer 的显式端口占用校验，避免 SO_REUSEADDR 场景下“启动成功但服务不可用”。
        if (isPortInUsing(serverIp, serverPort)) {
            throw new IllegalStateException(format(
                    "address[%s:%s] already in using, server bind failed.",
                    serverIp,
                    serverPort
            ));
        }

        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

        // Jetty 的 Servlet 调用运行在线程池而不是网络 IO 线程。Netty 下显式隔离模块执行，
        // 防止 reset/flush/用户模块中的阻塞操作卡住 EventLoop，保持原来的并发语义。
        final int moduleThreads = Math.max(8, Math.min(64, Runtime.getRuntime().availableProcessors() * 2));
        moduleExecutorGroup = new DefaultEventExecutorGroup(moduleThreads);

        final String contextPath = "/sandbox/" + cfg.getNamespace();
        final String moduleHttpPath = contextPath + "/module/http";
        logger.info("initializing http-handler. path={}/*", moduleHttpPath);

        final ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_REUSEADDR, false)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline()
                                .addLast("http-codec", new HttpServerCodec())
                                .addLast("http-aggregator", new HttpObjectAggregator(MAX_HTTP_CONTENT_LENGTH))
                                .addLast(moduleExecutorGroup,
                                        "sandbox-module-http",
                                        new NettyModuleHttpHandler(cfg, jvmSandbox.getCoreModuleManager()));
                    }
                });

        serverChannel = bootstrap.bind(new InetSocketAddress(serverIp, serverPort)).sync().channel();
    }

    @Override
    public synchronized void bind(final CoreConfigure cfg, final Instrumentation inst) throws IOException {
        this.cfg = cfg;
        try {
            initializer.initProcess(() -> {
                LogbackUtils.init(
                        cfg.getNamespace(),
                        cfg.getCfgLibPath() + File.separator + "sandbox-logback.xml"
                );
                logger.info("initializing server. cfg={}", cfg);
                jvmSandbox = new JvmSandbox(cfg, inst);
                initHttpServer();
            });

            // 与旧实现一致：HTTP 端口成功监听后再初始化/重置模块。
            try {
                jvmSandbox.getCoreModuleManager().reset();
            } catch (Throwable cause) {
                logger.warn("reset occur error when initializing.", cause);
            }

            final InetSocketAddress local = getLocal();
            logger.info("initialized server. actual bind to {}:{}",
                    local.getHostName(),
                    local.getPort()
            );
        } catch (Throwable cause) {
            // initProcess 失败时状态仍为 NEW；必须主动回收已创建的 Netty 线程和 Channel。
            shutdownNettyResources();
            logger.warn("initialize server failed.", cause);
            throw new IOException("server bind failed.", cause);
        }

        logger.info("{} bind success.", this);
    }

    private void shutdownNettyResources() {
        final Channel channel = serverChannel;
        serverChannel = null;
        if (null != channel) {
            channel.close().syncUninterruptibly();
        }

        final EventExecutorGroup executorGroup = moduleExecutorGroup;
        moduleExecutorGroup = null;
        if (null != executorGroup) {
            executorGroup.shutdownGracefully().syncUninterruptibly();
        }

        final EventLoopGroup workers = workerGroup;
        workerGroup = null;
        if (null != workers) {
            workers.shutdownGracefully().syncUninterruptibly();
        }

        final EventLoopGroup bosses = bossGroup;
        bossGroup = null;
        if (null != bosses) {
            bosses.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Override
    public void destroy() {
        if (null != jvmSandbox) {
            jvmSandbox.destroy();
        }

        if (isBind()) {
            try {
                unbind();
            } catch (IOException e) {
                logger.warn("{} unBind failed when destroy.", this, e);
            }
        } else {
            // bind 失败后 destroy() 也应是幂等的，避免残留资源。
            shutdownNettyResources();
        }

        LogbackUtils.destroy();
    }

    @Override
    public String toString() {
        return null == cfg
                ? "server[unbound]"
                : format("server[%s:%s]", cfg.getServerIp(), cfg.getServerPort());
    }
}
