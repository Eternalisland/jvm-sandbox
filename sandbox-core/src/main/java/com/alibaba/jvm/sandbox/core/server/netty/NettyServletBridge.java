package com.alibaba.jvm.sandbox.core.server.netty;

import com.alibaba.jvm.sandbox.core.CoreConfigure;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Netty 与 Sandbox 历史 Servlet 模块 API 之间的兼容桥。
 *
 * <p>不能为了移除 Jetty 就把模块方法参数改成 Netty 的 FullHttpRequest/FullHttpResponse：
 * 已发布的用户模块可能仍声明 HttpServletRequest、HttpServletResponse、PrintWriter 或 OutputStream。
 * 因此网络层改为 Netty 后，这里继续提供标准 Jakarta Servlet 接口视图，避免破坏模块二进制/源码契约。</p>
 *
 * <p>该桥接对象只在一次请求调用期间有效，与传统 Servlet request/response 生命周期一致。
 * NettyModuleHttpHandlerTest 覆盖参数、Header、Body、状态码和 writer/output stream 等关键兼容路径。</p>
 */
final class NettyServletBridge {

    private NettyServletBridge() {
    }

    static RequestAdapter newRequest(final FullHttpRequest request,
                                     final ChannelHandlerContext ctx,
                                     final CoreConfigure cfg,
                                     final String contextPath,
                                     final String servletPath,
                                     final String pathInfo) {
        return new RequestAdapter(request, ctx, cfg, contextPath, servletPath, pathInfo);
    }

    static ResponseAdapter newResponse(final CoreConfigure cfg) {
        return new ResponseAdapter(cfg.getServerCharset());
    }

    static final class RequestAdapter implements InvocationHandler {

        private final FullHttpRequest request;
        private final ChannelHandlerContext ctx;
        private final QueryStringDecoder uriDecoder;
        private final String contextPath;
        private final String servletPath;
        private final String pathInfo;
        private final String queryString;
        private final byte[] body;
        private final Map<String, String[]> parameterMap;
        private final Map<String, Object> attributes = new LinkedHashMap<>();
        private final HttpServletRequest servletRequest;
        private String characterEncoding;

        private RequestAdapter(final FullHttpRequest request,
                               final ChannelHandlerContext ctx,
                               final CoreConfigure cfg,
                               final String contextPath,
                               final String servletPath,
                               final String pathInfo) {
            this.request = request;
            this.ctx = ctx;
            this.characterEncoding = resolveRequestCharset(request, cfg.getServerCharset()).name();
            this.uriDecoder = new QueryStringDecoder(request.uri(), charset());
            this.contextPath = contextPath;
            this.servletPath = servletPath;
            this.pathInfo = pathInfo;
            this.queryString = extractQueryString(request.uri());
            this.body = ByteBufUtil.getBytes(request.content());
            this.parameterMap = buildParameterMap(request, uriDecoder, body, charset());
            this.servletRequest = (HttpServletRequest) Proxy.newProxyInstance(
                    HttpServletRequest.class.getClassLoader(),
                    new Class<?>[]{HttpServletRequest.class},
                    this
            );
        }

        HttpServletRequest servletRequest() {
            return servletRequest;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            final String name = method.getName();

            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(name)) {
                    return "NettyHttpServletRequest[" + request.method() + " " + request.uri() + "]";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (null == args ? null : args[0]);
                }
            }

            switch (name) {
                case "getMethod":
                    return request.method().name();
                case "getRequestURI":
                    return uriDecoder.path();
                case "getRequestURL":
                    return new StringBuffer(buildRequestUrl());
                case "getContextPath":
                    return contextPath;
                case "getServletPath":
                    return servletPath;
                case "getPathInfo":
                    return pathInfo;
                case "getPathTranslated":
                    return null;
                case "getQueryString":
                    return queryString;
                case "getProtocol":
                    return request.protocolVersion().text();
                case "getScheme":
                    return isSecureRequest() ? "https" : "http";
                case "isSecure":
                    return isSecureRequest();
                case "getServerName":
                    return hostAndPort().host;
                case "getServerPort":
                    return hostAndPort().port;
                case "getRemoteAddr":
                    return socketHost(ctx.channel().remoteAddress(), "0.0.0.0");
                case "getRemoteHost":
                    return socketHost(ctx.channel().remoteAddress(), "0.0.0.0");
                case "getRemotePort":
                    return socketPort(ctx.channel().remoteAddress(), 0);
                case "getLocalAddr":
                    return socketHost(ctx.channel().localAddress(), "0.0.0.0");
                case "getLocalName":
                    return socketHost(ctx.channel().localAddress(), "0.0.0.0");
                case "getLocalPort":
                    return socketPort(ctx.channel().localAddress(), 0);
                case "getCharacterEncoding":
                    return characterEncoding;
                case "setCharacterEncoding":
                    if (null != args && null != args[0]) {
                        // 与 Servlet 一致：非法编码通过 UnsupportedEncodingException/等价异常暴露给调用方。
                        Charset.forName(String.valueOf(args[0]));
                        characterEncoding = String.valueOf(args[0]);
                    }
                    return null;
                case "getContentLength":
                    return body.length > Integer.MAX_VALUE ? -1 : body.length;
                case "getContentLengthLong":
                    return (long) body.length;
                case "getContentType":
                    return request.headers().get(HttpHeaderNames.CONTENT_TYPE);
                case "getInputStream":
                    return new ByteArrayServletInputStream(body);
                case "getReader":
                    return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset()));
                case "getParameter": {
                    final String[] values = parameterMap.get(String.valueOf(args[0]));
                    return null == values || 0 == values.length ? null : values[0];
                }
                case "getParameterValues": {
                    final String[] values = parameterMap.get(String.valueOf(args[0]));
                    return null == values ? null : values.clone();
                }
                case "getParameterNames":
                    return Collections.enumeration(parameterMap.keySet());
                case "getParameterMap":
                    return parameterMap;
                case "getHeader":
                    return request.headers().get(String.valueOf(args[0]));
                case "getHeaders":
                    return Collections.enumeration(request.headers().getAll(String.valueOf(args[0])));
                case "getHeaderNames":
                    return Collections.enumeration(request.headers().names());
                case "getIntHeader": {
                    final String value = request.headers().get(String.valueOf(args[0]));
                    return null == value ? -1 : Integer.parseInt(value);
                }
                case "getDateHeader":
                    return getDateHeader(String.valueOf(args[0]));
                case "getCookies":
                    return requestCookies();
                case "getAttribute":
                    return attributes.get(String.valueOf(args[0]));
                case "getAttributeNames":
                    return Collections.enumeration(attributes.keySet());
                case "setAttribute":
                    if (null == args[1]) {
                        attributes.remove(String.valueOf(args[0]));
                    } else {
                        attributes.put(String.valueOf(args[0]), args[1]);
                    }
                    return null;
                case "removeAttribute":
                    attributes.remove(String.valueOf(args[0]));
                    return null;
                case "getLocale":
                    return resolveLocale();
                case "getLocales":
                    return Collections.enumeration(Collections.singletonList(resolveLocale()));
                case "getAuthType":
                case "getRemoteUser":
                case "getRequestedSessionId":
                case "getSession":
                case "getServletContext":
                case "getRequestDispatcher":
                case "getAsyncContext":
                case "getUserPrincipal":
                case "getHttpServletMapping":
                case "newPushBuilder":
                case "getServletConnection":
                    return null;
                case "isUserInRole":
                case "isRequestedSessionIdValid":
                case "isRequestedSessionIdFromCookie":
                case "isRequestedSessionIdFromURL":
                case "isAsyncStarted":
                case "isAsyncSupported":
                    return false;
                case "authenticate":
                    return false;
                case "changeSessionId":
                    return null;
                case "login":
                case "logout":
                    return null;
                case "getParts":
                    return Collections.emptyList();
                case "getPart":
                    return null;
                case "upgrade":
                    throw new ServletException("HTTP upgrade is not supported by the sandbox control endpoint.");
                case "startAsync":
                    throw new IllegalStateException("Async Servlet is not supported by the sandbox control endpoint.");
                case "getDispatcherType":
                    return DispatcherType.REQUEST;
                case "getRequestId":
                case "getProtocolRequestId":
                    return Integer.toHexString(System.identityHashCode(this));
                case "getTrailerFields":
                    return Collections.emptyMap();
                case "isTrailerFieldsReady":
                    return true;
                default:
                    // Proxy keeps the full Servlet type contract loadable. For container-specific/rare methods,
                    // return the Java default rather than coupling modules to Netty implementation classes.
                    return defaultValue(method.getReturnType());
            }
        }

        private Charset charset() {
            try {
                return Charset.forName(characterEncoding);
            } catch (Exception ignored) {
                return StandardCharsets.UTF_8;
            }
        }

        private boolean isSecureRequest() {
            return null != ctx.pipeline().get("ssl");
        }

        private String buildRequestUrl() {
            final HostAndPort hp = hostAndPort();
            final String scheme = isSecureRequest() ? "https" : "http";
            final boolean defaultPort = ("http".equals(scheme) && 80 == hp.port)
                    || ("https".equals(scheme) && 443 == hp.port);
            return scheme + "://" + hp.host + (defaultPort ? "" : ":" + hp.port) + uriDecoder.path();
        }

        private HostAndPort hostAndPort() {
            final String hostHeader = request.headers().get(HttpHeaderNames.HOST);
            if (null != hostHeader && !hostHeader.isBlank()) {
                final String value = hostHeader.trim();
                if (value.startsWith("[")) {
                    final int close = value.indexOf(']');
                    if (close > 0) {
                        final String host = value.substring(1, close);
                        final int port = close + 1 < value.length() && ':' == value.charAt(close + 1)
                                ? parsePort(value.substring(close + 2), defaultServerPort())
                                : defaultServerPort();
                        return new HostAndPort(host, port);
                    }
                }
                final int colon = value.lastIndexOf(':');
                if (colon > 0 && value.indexOf(':') == colon) {
                    return new HostAndPort(
                            value.substring(0, colon),
                            parsePort(value.substring(colon + 1), defaultServerPort())
                    );
                }
                return new HostAndPort(value, defaultServerPort());
            }
            return new HostAndPort(
                    socketHost(ctx.channel().localAddress(), "127.0.0.1"),
                    socketPort(ctx.channel().localAddress(), defaultServerPort())
            );
        }

        private int defaultServerPort() {
            return isSecureRequest() ? 443 : 80;
        }

        private long getDateHeader(final String name) {
            final String value = request.headers().get(name);
            if (null == value) {
                return -1L;
            }
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant()
                        .toEpochMilli();
            } catch (DateTimeParseException cause) {
                throw new IllegalArgumentException("Invalid HTTP date header: " + value, cause);
            }
        }

        private Locale resolveLocale() {
            final String acceptLanguage = request.headers().get(HttpHeaderNames.ACCEPT_LANGUAGE);
            if (null == acceptLanguage || acceptLanguage.isBlank()) {
                return Locale.getDefault();
            }
            final String first = acceptLanguage.split(",", 2)[0].split(";", 2)[0].trim();
            final Locale locale = Locale.forLanguageTag(first);
            return locale.getLanguage().isEmpty() ? Locale.getDefault() : locale;
        }

        private Cookie[] requestCookies() {
            final String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
            if (null == cookieHeader || cookieHeader.isBlank()) {
                return null;
            }
            final List<Cookie> cookies = new ArrayList<>();
            for (final String part : cookieHeader.split(";")) {
                final int equals = part.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                final String name = part.substring(0, equals).trim();
                final String value = part.substring(equals + 1).trim();
                if (!name.isEmpty()) {
                    try {
                        cookies.add(new Cookie(name, value));
                    } catch (IllegalArgumentException ignored) {
                        // 与容器解析行为一致：忽略格式非法的单个 Cookie，不影响整个请求。
                    }
                }
            }
            return cookies.isEmpty() ? null : cookies.toArray(new Cookie[0]);
        }
    }

    static final class ResponseAdapter implements InvocationHandler {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final HttpHeaders headers = new DefaultHttpHeaders();
        private final HttpServletResponse servletResponse;
        private int status = HttpServletResponse.SC_OK;
        private String characterEncoding;
        private String contentType;
        private Locale locale = Locale.getDefault();
        private int bufferSize = 8192;
        private boolean committed;
        private PrintWriter writer;
        private ByteArrayServletOutputStream outputStream;
        private Supplier<Map<String, String>> trailerFields;

        private ResponseAdapter(final Charset charset) {
            this.characterEncoding = charset.name();
            this.servletResponse = (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(),
                    new Class<?>[]{HttpServletResponse.class},
                    this
            );
        }

        HttpServletResponse servletResponse() {
            return servletResponse;
        }

        FullHttpResponse toNettyResponse(final HttpVersion version, final boolean keepAlive) {
            flushBodyQuietly();

            if (null != trailerFields) {
                final Map<String, String> trailers = trailerFields.get();
                if (null != trailers) {
                    trailers.forEach(headers::set);
                }
            }

            if (null != contentType && !headers.contains(HttpHeaderNames.CONTENT_TYPE)) {
                headers.set(HttpHeaderNames.CONTENT_TYPE, normalizedContentType());
            }

            final byte[] bytes = body.toByteArray();
            final FullHttpResponse response = new DefaultFullHttpResponse(
                    version,
                    HttpResponseStatus.valueOf(status),
                    Unpooled.wrappedBuffer(bytes)
            );
            response.headers().set(headers);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            HttpUtil.setKeepAlive(response, keepAlive);
            committed = true;
            return response;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            final String name = method.getName();

            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(name)) {
                    return "NettyHttpServletResponse[status=" + status + "]";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == (null == args ? null : args[0]);
                }
            }

            /*
             * TEST-DRIVEN FIX (NettyServletBridgeTest#shouldFreezeStatusHeadersAndCharsetAfterResponseIsCommitted):
             * Jetty/Servlet containers ignore ordinary response metadata setters after the response is committed.
             * The first Netty bridge implementation kept mutating status/headers after flushBuffer(), which changed
             * observable module behavior. Keep reset/sendError/sendRedirect on their existing IllegalStateException
             * path, but make ordinary metadata mutations no-ops once committed.
             */
            if (committed && isIgnoredMetadataMutationAfterCommit(name)) {
                return null;
            }

            switch (name) {
                case "setStatus":
                    ensureMutableHeaders();
                    status = (Integer) args[0];
                    return null;
                case "getStatus":
                    return status;
                case "sendError":
                    sendError((Integer) args[0], null != args && args.length > 1 ? String.valueOf(args[1]) : null);
                    return null;
                case "sendRedirect":
                    ensureNotCommitted();
                    resetBufferInternal();
                    status = HttpServletResponse.SC_FOUND;
                    headers.set(HttpHeaderNames.LOCATION, String.valueOf(args[0]));
                    return null;
                case "setHeader":
                    ensureMutableHeaders();
                    setHeader(String.valueOf(args[0]), null == args[1] ? null : String.valueOf(args[1]));
                    return null;
                case "addHeader":
                    ensureMutableHeaders();
                    addHeader(String.valueOf(args[0]), null == args[1] ? null : String.valueOf(args[1]));
                    return null;
                case "setIntHeader":
                    ensureMutableHeaders();
                    headers.setInt(String.valueOf(args[0]), (Integer) args[1]);
                    return null;
                case "addIntHeader":
                    ensureMutableHeaders();
                    headers.addInt(String.valueOf(args[0]), (Integer) args[1]);
                    return null;
                case "setDateHeader":
                    ensureMutableHeaders();
                    headers.set(String.valueOf(args[0]), formatHttpDate((Long) args[1]));
                    return null;
                case "addDateHeader":
                    ensureMutableHeaders();
                    headers.add(String.valueOf(args[0]), formatHttpDate((Long) args[1]));
                    return null;
                case "containsHeader":
                    return headers.contains(String.valueOf(args[0]));
                case "getHeader":
                    return headers.get(String.valueOf(args[0]));
                case "getHeaders":
                    return Collections.unmodifiableList(headers.getAll(String.valueOf(args[0])));
                case "getHeaderNames":
                    return Collections.unmodifiableSet(new LinkedHashSet<>(headers.names()));
                case "addCookie":
                    ensureMutableHeaders();
                    headers.add(HttpHeaderNames.SET_COOKIE, encodeCookie((Cookie) args[0]));
                    return null;
                case "encodeURL":
                case "encodeUrl":
                case "encodeRedirectURL":
                case "encodeRedirectUrl":
                    return args[0];
                case "getCharacterEncoding":
                    return characterEncoding;
                case "setCharacterEncoding":
                    ensureMutableHeaders();
                    if (null == writer && null != args[0]) {
                        Charset.forName(String.valueOf(args[0]));
                        characterEncoding = String.valueOf(args[0]);
                    }
                    return null;
                case "getContentType":
                    return contentType;
                case "setContentType":
                    ensureMutableHeaders();
                    setContentType(null == args[0] ? null : String.valueOf(args[0]));
                    return null;
                case "setContentLength":
                    ensureMutableHeaders();
                    headers.setInt(HttpHeaderNames.CONTENT_LENGTH, (Integer) args[0]);
                    return null;
                case "setContentLengthLong":
                    ensureMutableHeaders();
                    headers.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(args[0]));
                    return null;
                case "getWriter":
                    return writer();
                case "getOutputStream":
                    return outputStream();
                case "setBufferSize":
                    if (body.size() > 0 || committed) {
                        throw new IllegalStateException("Response body has already been written.");
                    }
                    bufferSize = (Integer) args[0];
                    return null;
                case "getBufferSize":
                    return bufferSize;
                case "flushBuffer":
                    flushBody();
                    committed = true;
                    return null;
                case "resetBuffer":
                    ensureNotCommitted();
                    resetBufferInternal();
                    return null;
                case "isCommitted":
                    return committed;
                case "reset":
                    ensureNotCommitted();
                    resetBufferInternal();
                    headers.clear();
                    status = HttpServletResponse.SC_OK;
                    contentType = null;
                    return null;
                case "setLocale":
                    ensureMutableHeaders();
                    locale = null == args[0] ? Locale.getDefault() : (Locale) args[0];
                    return null;
                case "getLocale":
                    return locale;
                case "setTrailerFields":
                    trailerFields = (Supplier<Map<String, String>>) args[0];
                    return null;
                case "getTrailerFields":
                    return trailerFields;
                default:
                    return defaultValue(method.getReturnType());
            }
        }

        private void sendError(final int statusCode, final String message) throws IOException {
            ensureNotCommitted();
            resetBufferInternal();
            status = statusCode;
            if (null != message && !message.isEmpty()) {
                body.write(message.getBytes(charset()));
            }
        }

        private PrintWriter writer() {
            if (null != outputStream) {
                throw new IllegalStateException("getOutputStream() has already been called for this response.");
            }
            if (null == writer) {
                writer = new PrintWriter(new OutputStreamWriter(body, charset()));
            }
            return writer;
        }

        private ServletOutputStream outputStream() {
            if (null != writer) {
                throw new IllegalStateException("getWriter() has already been called for this response.");
            }
            if (null == outputStream) {
                outputStream = new ByteArrayServletOutputStream(body);
            }
            return outputStream;
        }

        private void flushBody() throws IOException {
            if (null != writer) {
                writer.flush();
            }
            if (null != outputStream) {
                outputStream.flush();
            }
        }

        private void flushBodyQuietly() {
            try {
                flushBody();
            } catch (IOException ignored) {
                // ByteArrayOutputStream flush 不会失败；保留 IOException 兼容 ServletOutputStream 契约。
            }
        }

        private void resetBufferInternal() {
            body.reset();
            writer = null;
            outputStream = null;
        }

        private void setHeader(final String name, final String value) {
            if (null == value) {
                headers.remove(name);
            } else {
                headers.set(name, value);
            }
            syncSpecialHeader(name, value);
        }

        private void addHeader(final String name, final String value) {
            if (null != value) {
                headers.add(name, value);
                syncSpecialHeader(name, value);
            }
        }

        private void syncSpecialHeader(final String name, final String value) {
            if (HttpHeaderNames.CONTENT_TYPE.toString().equalsIgnoreCase(name)) {
                setContentType(value);
            }
        }

        private void setContentType(final String value) {
            contentType = value;
            if (null == value) {
                headers.remove(HttpHeaderNames.CONTENT_TYPE);
                return;
            }
            final String charsetValue = extractCharset(value);
            if (null != charsetValue && null == writer) {
                Charset.forName(charsetValue);
                characterEncoding = charsetValue;
            }
            headers.set(HttpHeaderNames.CONTENT_TYPE, normalizedContentType());
        }

        private String normalizedContentType() {
            if (null == contentType) {
                return null;
            }
            return null != extractCharset(contentType)
                    ? contentType
                    : contentType + ";charset=" + characterEncoding;
        }

        private Charset charset() {
            try {
                return Charset.forName(characterEncoding);
            } catch (Exception ignored) {
                return StandardCharsets.UTF_8;
            }
        }

        private boolean isIgnoredMetadataMutationAfterCommit(final String methodName) {
            switch (methodName) {
                case "setStatus":
                case "setHeader":
                case "addHeader":
                case "setIntHeader":
                case "addIntHeader":
                case "setDateHeader":
                case "addDateHeader":
                case "addCookie":
                case "setCharacterEncoding":
                case "setContentType":
                case "setContentLength":
                case "setContentLengthLong":
                case "setLocale":
                case "setTrailerFields":
                    return true;
                default:
                    return false;
            }
        }

        private void ensureMutableHeaders() {
            // Compatibility guard is centralized at invoke() so individual setter branches stay readable.
        }

        private void ensureNotCommitted() {
            if (committed) {
                throw new IllegalStateException("Response has already been committed.");
            }
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(final byte[] bytes) {
            this.input = new ByteArrayInputStream(bytes);
        }

        @Override
        public int read() {
            return input.read();
        }

        @Override
        public int read(final byte[] b, final int off, final int len) {
            return input.read(b, off, len);
        }

        @Override
        public int available() {
            return input.available();
        }

        @Override
        public boolean isFinished() {
            return 0 == input.available();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(final ReadListener readListener) {
            if (null == readListener) {
                throw new IllegalArgumentException("readListener");
            }
            try {
                if (isFinished()) {
                    readListener.onAllDataRead();
                } else {
                    readListener.onDataAvailable();
                }
            } catch (IOException cause) {
                readListener.onError(cause);
            }
        }
    }

    private static final class ByteArrayServletOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream output;

        private ByteArrayServletOutputStream(final ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(final int b) {
            output.write(b);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) {
            output.write(b, off, len);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(final WriteListener writeListener) {
            if (null == writeListener) {
                throw new IllegalArgumentException("writeListener");
            }
            try {
                writeListener.onWritePossible();
            } catch (IOException cause) {
                writeListener.onError(cause);
            }
        }
    }

    private static Charset resolveRequestCharset(final FullHttpRequest request, final Charset fallback) {
        final String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        final String charset = extractCharset(contentType);
        if (null == charset) {
            return fallback;
        }
        try {
            return Charset.forName(charset);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, String[]> buildParameterMap(final FullHttpRequest request,
                                                            final QueryStringDecoder uriDecoder,
                                                            final byte[] body,
                                                            final Charset charset) {
        final Map<String, List<String>> values = new LinkedHashMap<>();
        mergeParameters(values, uriDecoder.parameters());

        final String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (body.length > 0
                && null != contentType
                && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            final QueryStringDecoder formDecoder = new QueryStringDecoder("/?" + new String(body, charset), charset);
            mergeParameters(values, formDecoder.parameters());
        }

        final Map<String, String[]> result = new LinkedHashMap<>();
        values.forEach((key, list) -> result.put(key, list.toArray(new String[0])));
        return Collections.unmodifiableMap(result);
    }

    private static void mergeParameters(final Map<String, List<String>> target,
                                        final Map<String, List<String>> source) {
        source.forEach((key, sourceValues) ->
                target.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(sourceValues));
    }

    private static String extractQueryString(final String uri) {
        final int queryIndex = uri.indexOf('?');
        if (queryIndex < 0 || queryIndex + 1 >= uri.length()) {
            return queryIndex < 0 ? null : "";
        }
        final int fragment = uri.indexOf('#', queryIndex + 1);
        return fragment < 0 ? uri.substring(queryIndex + 1) : uri.substring(queryIndex + 1, fragment);
    }

    private static String extractCharset(final String contentType) {
        if (null == contentType) {
            return null;
        }
        for (final String part : contentType.split(";")) {
            final String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                String value = trimmed.substring("charset=".length()).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String formatHttpDate(final long millis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC)
        );
    }

    private static String encodeCookie(final Cookie cookie) {
        final StringBuilder builder = new StringBuilder()
                .append(cookie.getName())
                .append('=')
                .append(null == cookie.getValue() ? "" : cookie.getValue());
        if (null != cookie.getPath()) {
            builder.append("; Path=").append(cookie.getPath());
        }
        if (null != cookie.getDomain()) {
            builder.append("; Domain=").append(cookie.getDomain());
        }
        if (cookie.getMaxAge() >= 0) {
            builder.append("; Max-Age=").append(cookie.getMaxAge());
        }
        if (cookie.getSecure()) {
            builder.append("; Secure");
        }
        if (cookie.isHttpOnly()) {
            builder.append("; HttpOnly");
        }
        return builder.toString();
    }

    private static String socketHost(final SocketAddress address, final String fallback) {
        if (address instanceof InetSocketAddress) {
            final InetSocketAddress inet = (InetSocketAddress) address;
            return null != inet.getAddress() ? inet.getAddress().getHostAddress() : inet.getHostString();
        }
        return fallback;
    }

    private static int socketPort(final SocketAddress address, final int fallback) {
        return address instanceof InetSocketAddress ? ((InetSocketAddress) address).getPort() : fallback;
    }

    private static int parsePort(final String value, final int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (boolean.class == type) {
            return false;
        }
        if (char.class == type) {
            return '\0';
        }
        if (byte.class == type) {
            return (byte) 0;
        }
        if (short.class == type) {
            return (short) 0;
        }
        if (int.class == type) {
            return 0;
        }
        if (long.class == type) {
            return 0L;
        }
        if (float.class == type) {
            return 0F;
        }
        if (double.class == type) {
            return 0D;
        }
        return null;
    }

    private static final class HostAndPort {
        private final String host;
        private final int port;

        private HostAndPort(final String host, final int port) {
            this.host = host;
            this.port = port;
        }
    }
}
