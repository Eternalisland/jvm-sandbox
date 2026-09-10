package com.alibaba.jvm.sandbox.core.server.netty;

import com.alibaba.jvm.sandbox.api.annotation.Command;
import com.alibaba.jvm.sandbox.api.http.Http;
import com.alibaba.jvm.sandbox.core.CoreConfigure;
import com.alibaba.jvm.sandbox.core.CoreModule;
import com.alibaba.jvm.sandbox.core.CoreModule.ReleaseResource;
import com.alibaba.jvm.sandbox.core.manager.CoreModuleManager;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.jvm.sandbox.api.util.GaStringUtils.matching;

/**
 * 使用 Netty 接收请求，但保持原 ModuleHttpServlet 的模块分发语义。
 *
 * <p>迁移原则是“换容器，不换协议”：URL 仍为
 * /sandbox/{namespace}/module/http/{moduleId}/{command}，@Command 与 @Http 的匹配规则、
 * Map/String/PrintWriter/OutputStream/HttpServletRequest/HttpServletResponse 参数注入规则全部沿用。</p>
 */
final class NettyModuleHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final String SLASH = "/";
    private static final String SERVLET_PATH = "/module/http";

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final CoreConfigure cfg;
    private final CoreModuleManager coreModuleManager;
    private final String contextPath;
    private final String moduleHttpPath;

    NettyModuleHttpHandler(final CoreConfigure cfg, final CoreModuleManager coreModuleManager) {
        this.cfg = cfg;
        this.coreModuleManager = coreModuleManager;
        this.contextPath = "/sandbox/" + cfg.getNamespace();
        this.moduleHttpPath = contextPath + SERVLET_PATH;
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final FullHttpRequest request) {
        final boolean keepAlive = HttpUtil.isKeepAlive(request);
        final NettyServletBridge.ResponseAdapter response = NettyServletBridge.newResponse(cfg);

        try {
            if (!request.decoderResult().isSuccess()) {
                response.servletResponse().sendError(HttpServletResponse.SC_BAD_REQUEST);
                writeResponse(ctx, request, response, keepAlive);
                return;
            }

            final Http.Method sandboxMethod;
            if (HttpMethod.GET.equals(request.method())) {
                sandboxMethod = Http.Method.GET;
            } else if (HttpMethod.POST.equals(request.method())) {
                sandboxMethod = Http.Method.POST;
            } else {
                response.servletResponse().setHeader(HttpHeaderNames.ALLOW.toString(), "GET, POST");
                response.servletResponse().sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                writeResponse(ctx, request, response, keepAlive);
                return;
            }

            final QueryStringDecoder decoder = new QueryStringDecoder(request.uri(), cfg.getServerCharset());
            final String requestPath = decoder.path();
            if (!requestPath.startsWith(moduleHttpPath + SLASH)) {
                response.servletResponse().sendError(HttpServletResponse.SC_NOT_FOUND);
                writeResponse(ctx, request, response, keepAlive);
                return;
            }

            // 对应旧 Servlet 映射 /module/http/* 的 HttpServletRequest#getPathInfo()。
            final String pathInfo = requestPath.substring(moduleHttpPath.length());
            final NettyServletBridge.RequestAdapter requestAdapter = NettyServletBridge.newRequest(
                    request,
                    ctx,
                    cfg,
                    contextPath,
                    SERVLET_PATH,
                    pathInfo
            );

            doMethod(
                    requestAdapter.servletRequest(),
                    response.servletResponse(),
                    sandboxMethod
            );
        } catch (Throwable cause) {
            logger.warn("process sandbox http request failed. uri={}", request.uri(), cause);
            try {
                if (!response.servletResponse().isCommitted()) {
                    response.servletResponse().reset();
                    response.servletResponse().sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            } catch (Throwable secondary) {
                logger.warn("build sandbox http error response failed. uri={}", request.uri(), secondary);
            }
        }

        writeResponse(ctx, request, response, keepAlive);
    }

    private void writeResponse(final ChannelHandlerContext ctx,
                               final FullHttpRequest request,
                               final NettyServletBridge.ResponseAdapter response,
                               final boolean keepAlive) {
        final FullHttpResponse nettyResponse = response.toNettyResponse(request.protocolVersion(), keepAlive);
        final ChannelFuture future = ctx.writeAndFlush(nettyResponse);
        if (!keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 下面的分发逻辑从原 ModuleHttpServlet 等价迁移。
     * 保留原有匹配与资源释放顺序，避免 transport migration 顺带改变用户模块行为。
     */
    private void doMethod(final HttpServletRequest req,
                          final HttpServletResponse resp,
                          final Http.Method expectHttpMethod) throws ServletException, IOException {
        final String path = req.getPathInfo();

        final String uniqueId = parseUniqueId(path);
        if (StringUtils.isBlank(uniqueId)) {
            logger.warn("path={} is not matched any module.", path);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        final CoreModule coreModule = coreModuleManager.get(uniqueId);
        if (null == coreModule) {
            logger.warn("path={} is matched module {}, but not existed.", path, uniqueId);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        final Method method = matchingModuleMethod(
                path,
                expectHttpMethod,
                uniqueId,
                coreModule.getModule().getClass()
        );
        if (null == method) {
            logger.warn("path={} is not matched any method in module {}", path, uniqueId);
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        } else {
            logger.debug("path={} is matched method {} in module {}", path, method.getName(), uniqueId);
        }

        final List<Closeable> autoCloseResources = coreModule.append(
                new ReleaseResource<List<Closeable>>(new ArrayList<>()) {
                    @Override
                    public void release() {
                        final List<Closeable> closeables = get();
                        if (CollectionUtils.isEmpty(closeables)) {
                            return;
                        }
                        for (final Closeable closeable : closeables) {
                            if (closeable instanceof Flushable) {
                                try {
                                    ((Flushable) closeable).flush();
                                } catch (Exception cause) {
                                    logger.warn("path={} flush I/O occur error!", path, cause);
                                }
                            }
                            IOUtils.closeQuietly(closeable);
                        }
                    }
                }
        );

        final Object[] parameterObjectArray = generateParameterObjectArray(
                autoCloseResources,
                method,
                req,
                resp
        );

        final boolean isAccessible = method.isAccessible();
        final ClassLoader oriThreadContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            method.setAccessible(true);
            Thread.currentThread().setContextClassLoader(coreModule.getLoader());
            method.invoke(coreModule.getModule(), parameterObjectArray);
            logger.debug("path={} invoke module {} method {} success.", path, uniqueId, method.getName());
        } catch (IllegalAccessException iae) {
            logger.warn("path={} invoke module {} method {} occur access denied.",
                    path, uniqueId, method.getName(), iae);
            throw new ServletException(iae);
        } catch (InvocationTargetException ite) {
            final Throwable targetCause = ite.getTargetException();
            logger.warn("path={} invoke module {} method {} occur error.",
                    path, uniqueId, method.getName(), targetCause);
            if (targetCause instanceof ServletException) {
                throw (ServletException) targetCause;
            }
            if (targetCause instanceof IOException) {
                throw (IOException) targetCause;
            }
            throw new ServletException(targetCause);
        } finally {
            Thread.currentThread().setContextClassLoader(oriThreadContextClassLoader);
            method.setAccessible(isAccessible);
            coreModule.release(autoCloseResources);
        }
    }

    private String parseUniqueId(final String path) {
        final String[] pathSegmentArray = StringUtils.split(path, "/");
        return ArrayUtils.getLength(pathSegmentArray) >= 1
                ? pathSegmentArray[0]
                : null;
    }

    private Method matchingModuleMethod(final String path,
                                        final Http.Method httpMethod,
                                        final String uniqueId,
                                        final Class<?> classOfModule) {
        for (final Method method : MethodUtils.getMethodsListWithAnnotation(classOfModule, Command.class)) {
            final Command commandAnnotation = method.getAnnotation(Command.class);
            if (null == commandAnnotation) {
                continue;
            }
            final String cmd = appendSlash(commandAnnotation.value());
            final String pathOfCmd = "/" + uniqueId + cmd;
            if (StringUtils.equals(path, pathOfCmd)) {
                return method;
            }
        }

        for (final Method method : MethodUtils.getMethodsListWithAnnotation(classOfModule, Http.class)) {
            final Http httpAnnotation = method.getAnnotation(Http.class);
            if (null == httpAnnotation) {
                continue;
            }
            final String cmd = appendSlash(httpAnnotation.value());
            final String pathPattern = "/" + uniqueId + cmd;
            if (ArrayUtils.contains(httpAnnotation.method(), httpMethod)
                    && matching(path, pathPattern)) {
                return method;
            }
        }
        return null;
    }

    private String appendSlash(String cmd) {
        if (!cmd.startsWith(SLASH)) {
            cmd = SLASH + cmd;
        }
        return cmd;
    }

    private boolean isMapWithGenericParameterTypes(final Method method,
                                                   final int parameterIndex,
                                                   final Class<?> keyClass,
                                                   final Class<?> valueClass) {
        final Type[] genericParameterTypes = method.getGenericParameterTypes();
        if (genericParameterTypes.length <= parameterIndex
                || !(genericParameterTypes[parameterIndex] instanceof ParameterizedType)) {
            return false;
        }
        final Type[] actualTypeArguments = ((ParameterizedType) genericParameterTypes[parameterIndex])
                .getActualTypeArguments();
        return actualTypeArguments.length == 2
                && keyClass.equals(actualTypeArguments[0])
                && valueClass.equals(actualTypeArguments[1]);
    }

    private Object[] generateParameterObjectArray(final List<Closeable> autoCloseResources,
                                                  final Method method,
                                                  final HttpServletRequest req,
                                                  final HttpServletResponse resp) throws IOException {
        final Class<?>[] parameterTypeArray = method.getParameterTypes();
        if (ArrayUtils.isEmpty(parameterTypeArray)) {
            return null;
        }

        final Object[] parameterObjectArray = new Object[parameterTypeArray.length];
        for (int index = 0; index < parameterObjectArray.length; index++) {
            final Class<?> parameterType = parameterTypeArray[index];

            if (HttpServletRequest.class.isAssignableFrom(parameterType)) {
                parameterObjectArray[index] = req;
            } else if (HttpServletResponse.class.isAssignableFrom(parameterType)) {
                parameterObjectArray[index] = resp;
            } else if (Map.class.isAssignableFrom(parameterType)
                    && isMapWithGenericParameterTypes(method, index, String.class, String[].class)) {
                parameterObjectArray[index] = req.getParameterMap();
            } else if (Map.class.isAssignableFrom(parameterType)
                    && isMapWithGenericParameterTypes(method, index, String.class, String.class)) {
                final Map<String, String> param = new HashMap<>();
                for (final Map.Entry<String, String[]> entry : req.getParameterMap().entrySet()) {
                    param.put(entry.getKey(), StringUtils.join(entry.getValue(), ","));
                }
                parameterObjectArray[index] = param;
            } else if (String.class.isAssignableFrom(parameterType)) {
                parameterObjectArray[index] = req.getQueryString();
            } else if (PrintWriter.class.isAssignableFrom(parameterType)) {
                final PrintWriter writer = resp.getWriter();
                autoCloseResources.add(writer);
                parameterObjectArray[index] = writer;
            } else if (OutputStream.class.isAssignableFrom(parameterType)) {
                final OutputStream output = resp.getOutputStream();
                autoCloseResources.add(output);
                parameterObjectArray[index] = output;
            }
        }
        return parameterObjectArray;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        logger.warn("sandbox netty channel error. remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
