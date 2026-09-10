package com.alibaba.jvm.sandbox.core.server.netty;

import com.alibaba.jvm.sandbox.core.CoreConfigure;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Servlet compatibility layer regression tests for the Jetty -> Netty migration.
 *
 * <p>Coverage rationale: user modules historically receive Servlet API objects even though the embedded
 * container has been replaced. These tests therefore verify observable Servlet behavior rather than Netty
 * implementation details: request metadata/parameters/headers/cookies/body, response charset/content-type,
 * commit semantics, redirects/errors and writer/output-stream exclusivity.</p>
 */
class NettyServletBridgeTest {

    @Test
    void shouldExposeServletRequestMetadataHeadersCookiesParametersAndBody() throws Exception {
        final CoreConfigure cfg = configure();
        final EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("capture", new ChannelInboundHandlerAdapter());
        final ChannelHandlerContext ctx = channel.pipeline().context("capture");

        final FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/sandbox/test/module/http/compat/read?name=%E5%98%9F%E5%98%9F&name=two",
                Unpooled.copiedBuffer("payload", StandardCharsets.UTF_8)
        );
        nettyRequest.headers().set(HttpHeaderNames.HOST, "sandbox.local:8820");
        nettyRequest.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        nettyRequest.headers().set(HttpHeaderNames.COOKIE, "sid=abc; theme=dark");
        nettyRequest.headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9");
        nettyRequest.headers().set("X-Count", "7");
        nettyRequest.headers().set(
                "X-Date",
                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.parse("2026-09-10T04:00:00Z"))
        );

        final HttpServletRequest request = NettyServletBridge.newRequest(
                nettyRequest,
                ctx,
                cfg,
                "/sandbox/test",
                "/module/http",
                "/compat/read"
        ).servletRequest();

        try {
            assertEquals("POST", request.getMethod());
            assertEquals("/sandbox/test", request.getContextPath());
            assertEquals("/module/http", request.getServletPath());
            assertEquals("/compat/read", request.getPathInfo());
            assertEquals("/sandbox/test/module/http/compat/read", request.getRequestURI());
            assertEquals("http://sandbox.local:8820/sandbox/test/module/http/compat/read", request.getRequestURL().toString());
            assertEquals("sandbox.local", request.getServerName());
            assertEquals(8820, request.getServerPort());
            assertEquals("嘟嘟", request.getParameter("name"));
            assertArrayEquals(new String[]{"嘟嘟", "two"}, request.getParameterValues("name"));
            assertEquals("7", request.getHeader("X-Count"));
            assertEquals(7, request.getIntHeader("X-Count"));
            assertTrue(request.getDateHeader("X-Date") > 0);
            assertEquals(Locale.forLanguageTag("zh-CN"), request.getLocale());
            assertEquals("payload", new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            final Cookie[] cookies = request.getCookies();
            assertEquals(2, cookies.length);
            assertEquals("sid", cookies[0].getName());
            assertEquals("abc", cookies[0].getValue());

            request.setAttribute("traceId", "t-1");
            assertEquals("t-1", request.getAttribute("traceId"));
            request.removeAttribute("traceId");
            assertEquals(null, request.getAttribute("traceId"));
        } finally {
            nettyRequest.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldPreserveResponseStatusHeadersCharsetCookieAndBody() throws Exception {
        final NettyServletBridge.ResponseAdapter adapter = NettyServletBridge.newResponse(configure());
        final HttpServletResponse response = adapter.servletResponse();

        response.setStatus(HttpServletResponse.SC_CREATED);
        response.setHeader("X-Test", "value");
        response.setContentType("text/plain");
        final Cookie cookie = new Cookie("sid", "abc");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        response.addCookie(cookie);
        response.getWriter().print("中文响应");

        final FullHttpResponse nettyResponse = adapter.toNettyResponse(HttpVersion.HTTP_1_1, true);
        try {
            assertEquals(HttpResponseStatus.CREATED, nettyResponse.status());
            assertEquals("value", nettyResponse.headers().get("X-Test"));
            assertEquals("text/plain;charset=UTF-8", nettyResponse.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertTrue(nettyResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("sid=abc"));
            assertTrue(nettyResponse.headers().get(HttpHeaderNames.SET_COOKIE).contains("HttpOnly"));
            assertEquals("中文响应", nettyResponse.content().toString(CharsetUtil.UTF_8));
            assertTrue(io.netty.handler.codec.http.HttpUtil.isKeepAlive(nettyResponse));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    void shouldRejectWriterAndOutputStreamMixedUsage() throws Exception {
        final HttpServletResponse writerResponse = NettyServletBridge.newResponse(configure()).servletResponse();
        writerResponse.getWriter();
        assertThrows(IllegalStateException.class, writerResponse::getOutputStream);

        final HttpServletResponse streamResponse = NettyServletBridge.newResponse(configure()).servletResponse();
        streamResponse.getOutputStream();
        assertThrows(IllegalStateException.class, streamResponse::getWriter);
    }

    @Test
    void shouldFreezeStatusHeadersAndCharsetAfterResponseIsCommitted() throws Exception {
        final NettyServletBridge.ResponseAdapter adapter = NettyServletBridge.newResponse(configure());
        final HttpServletResponse response = adapter.servletResponse();

        response.setStatus(HttpServletResponse.SC_CREATED);
        response.setHeader("X-Before", "before");
        response.setContentType("text/plain");
        response.getWriter().print("body");
        response.flushBuffer();
        assertTrue(response.isCommitted());

        // Servlet container semantics: setters after commit are ignored; reset/sendError are illegal.
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.setHeader("X-Before", "after");
        response.setHeader("X-After", "unexpected");
        response.setCharacterEncoding("UTF-16");
        response.setContentType("application/json");
        assertThrows(IllegalStateException.class, response::reset);
        assertThrows(IllegalStateException.class, () -> response.sendError(500));

        final FullHttpResponse nettyResponse = adapter.toNettyResponse(HttpVersion.HTTP_1_1, false);
        try {
            assertEquals(HttpResponseStatus.CREATED, nettyResponse.status());
            assertEquals("before", nettyResponse.headers().get("X-Before"));
            assertFalse(nettyResponse.headers().contains("X-After"));
            assertEquals("text/plain;charset=UTF-8", nettyResponse.headers().get(HttpHeaderNames.CONTENT_TYPE));
            assertEquals("body", nettyResponse.content().toString(CharsetUtil.UTF_8));
            assertFalse(io.netty.handler.codec.http.HttpUtil.isKeepAlive(nettyResponse));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    void shouldResetBodyAndReturnRedirectLocation() throws Exception {
        final NettyServletBridge.ResponseAdapter adapter = NettyServletBridge.newResponse(configure());
        final HttpServletResponse response = adapter.servletResponse();
        response.getWriter().print("must-be-cleared");
        response.sendRedirect("/next");

        final FullHttpResponse nettyResponse = adapter.toNettyResponse(HttpVersion.HTTP_1_1, true);
        try {
            assertEquals(HttpResponseStatus.FOUND, nettyResponse.status());
            assertEquals("/next", nettyResponse.headers().get(HttpHeaderNames.LOCATION));
            assertEquals(0, nettyResponse.content().readableBytes());
        } finally {
            nettyResponse.release();
        }
    }

    private static CoreConfigure configure() {
        return CoreConfigure.toConfigure("namespace=test;server.charset=UTF-8", null);
    }
}
