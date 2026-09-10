package com.alibaba.jvm.sandbox.core.server.netty;

import com.alibaba.jvm.sandbox.api.Module;
import com.alibaba.jvm.sandbox.api.ModuleException;
import com.alibaba.jvm.sandbox.api.annotation.Command;
import com.alibaba.jvm.sandbox.api.http.Http;
import com.alibaba.jvm.sandbox.core.CoreConfigure;
import com.alibaba.jvm.sandbox.core.CoreModule;
import com.alibaba.jvm.sandbox.core.manager.CoreModuleManager;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jetty -> Netty 功能兼容回归。
 *
 * <p>这些用例刻意覆盖原 ModuleHttpServlet 暴露给用户模块的契约，而不是只测 Netty 实现细节：
 * URL、@Command/@Http 匹配、query/form 参数合并、Servlet request/response 参数、writer/output stream、
 * 404/405 状态码。迁移后这些行为必须保持。</p>
 */
class NettyModuleHttpHandlerTest {

    @Test
    void shouldKeepCommandRouteAndServletParameterCompatibility() {
        final CompatModule module = new CompatModule();
        final EmbeddedChannel channel = newChannel(module);
        final FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/sandbox/test/module/http/compat/echo?name=alice&tag=a&tag=b"
        );
        request.headers().set(HttpHeaderNames.HOST, "127.0.0.1:8820");

        channel.writeInbound(request);
        final FullHttpResponse response = channel.readOutbound();
        try {
            assertEquals(HttpResponseStatus.CREATED, response.status());
            assertEquals("GET", response.headers().get("X-Method"));
            assertTrue(response.headers().get(HttpHeaderNames.CONTENT_TYPE).startsWith("text/plain"));
            assertEquals(
                    "alice|a,b|name=alice&tag=a&tag=b|alice|/compat/echo|/sandbox/test/module/http/compat/echo",
                    response.content().toString(CharsetUtil.UTF_8)
            );
        } finally {
            response.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldMergeQueryAndUrlEncodedPostParametersAndExposeRequestBody() {
        final CompatModule module = new CompatModule();
        final EmbeddedChannel channel = newChannel(module);
        final FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/sandbox/test/module/http/compat/form?q=query",
                Unpooled.copiedBuffer("q=body&x=1", StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=UTF-8");

        channel.writeInbound(request);
        final FullHttpResponse response = channel.readOutbound();
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("query,body|1|q=body&x=1", response.content().toString(CharsetUtil.UTF_8));
            assertArrayEquals(new String[]{"query", "body"}, module.lastQValues);
        } finally {
            response.release();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void shouldKeepHttpMethodMatchingAndContainerStatusCodes() {
        final CompatModule module = new CompatModule();

        final FullHttpResponse wrongMethod = exchange(
                module,
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        "/sandbox/test/module/http/compat/form"
                )
        );
        try {
            // @Http(form) 只允许 POST；和原 ModuleHttpServlet 一样，没有匹配到方法时返回 404。
            assertEquals(HttpResponseStatus.NOT_FOUND, wrongMethod.status());
        } finally {
            wrongMethod.release();
        }

        final FullHttpResponse unsupportedVerb = exchange(
                module,
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.DELETE,
                        "/sandbox/test/module/http/compat/echo"
                )
        );
        try {
            assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, unsupportedVerb.status());
            assertEquals("GET, POST", unsupportedVerb.headers().get(HttpHeaderNames.ALLOW));
        } finally {
            unsupportedVerb.release();
        }

        final FullHttpResponse wrongContext = exchange(
                module,
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        "/sandbox/other/module/http/compat/echo"
                )
        );
        try {
            assertEquals(HttpResponseStatus.NOT_FOUND, wrongContext.status());
        } finally {
            wrongContext.release();
        }
    }

    @Test
    void shouldReturnNotFoundForUnknownModuleAndEmptyModulePath() {
        final CompatModule module = new CompatModule();

        final FullHttpResponse unknownModule = exchange(
                module,
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        "/sandbox/test/module/http/missing/echo"
                )
        );
        try {
            assertEquals(HttpResponseStatus.NOT_FOUND, unknownModule.status());
        } finally {
            unknownModule.release();
        }

        final FullHttpResponse emptyPath = exchange(
                module,
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        "/sandbox/test/module/http/"
                )
        );
        try {
            assertEquals(HttpResponseStatus.NOT_FOUND, emptyPath.status());
        } finally {
            emptyPath.release();
        }
    }

    @Test
    void shouldReturnInternalServerErrorWhenModuleInvocationFails() {
        final FullHttpResponse response = exchange(
                new CompatModule(),
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        "/sandbox/test/module/http/compat/explode"
                )
        );
        try {
            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        } finally {
            response.release();
        }
    }

    @Test
    void shouldKeepWildcardHttpPathMatching() {
        final FullHttpResponse response = exchange(
                new CompatModule(),
                new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        HttpMethod.GET,
                        "/sandbox/test/module/http/compat/wild/child?id=9"
                )
        );
        try {
            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("wild:id=9", response.content().toString(CharsetUtil.UTF_8));
        } finally {
            response.release();
        }
    }

    @Test
    void shouldReturnBadRequestWhenNettyDecoderMarksRequestInvalid() {
        final DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.GET,
                "/sandbox/test/module/http/compat/echo"
        );
        request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("invalid request")));

        final FullHttpResponse response = exchange(new CompatModule(), request);
        try {
            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        } finally {
            response.release();
        }
    }

    private static FullHttpResponse exchange(final CompatModule module, final FullHttpRequest request) {
        final EmbeddedChannel channel = newChannel(module);
        channel.writeInbound(request);
        final FullHttpResponse response = channel.readOutbound();
        channel.finish();
        return response;
    }

    private static EmbeddedChannel newChannel(final CompatModule module) {
        final CoreConfigure cfg = CoreConfigure.toConfigure(
                "namespace=test;server.charset=UTF-8",
                null
        );
        final CoreModule coreModule = new CoreModule("compat", null, null, module);
        return new EmbeddedChannel(new NettyModuleHttpHandler(cfg, new SingleModuleManager(coreModule)));
    }

    private static final class CompatModule implements Module {

        private String[] lastQValues;

        @Command("echo")
        public void echo(final Map<String, String> params,
                         final String queryString,
                         final HttpServletRequest request,
                         final HttpServletResponse response,
                         final PrintWriter writer) {
            response.setStatus(HttpServletResponse.SC_CREATED);
            response.setHeader("X-Method", request.getMethod());
            response.setContentType("text/plain");
            writer.print(
                    params.get("name") + "|"
                            + params.get("tag") + "|"
                            + queryString + "|"
                            + request.getParameter("name") + "|"
                            + request.getPathInfo() + "|"
                            + request.getRequestURI()
            );
        }

        @Http(value = "/form", method = Http.Method.POST)
        public void form(final Map<String, String[]> params,
                         final HttpServletRequest request,
                         final OutputStream output) throws IOException {
            lastQValues = params.get("q");
            final String body = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            output.write((String.join(",", params.get("q"))
                    + "|" + params.get("x")[0]
                    + "|" + body).getBytes(StandardCharsets.UTF_8));
        }

        @Command("explode")
        public void explode() {
            throw new IllegalStateException("boom");
        }

        @Http(value = "/wild/*", method = Http.Method.GET)
        public void wildcard(final String queryString, final PrintWriter writer) {
            writer.print("wild:" + queryString);
        }
    }

    private static final class SingleModuleManager implements CoreModuleManager {

        private final CoreModule module;

        private SingleModuleManager(final CoreModule module) {
            this.module = module;
        }

        @Override
        public void flush(final boolean isForce) {
        }

        @Override
        public CoreModuleManager reset() {
            return this;
        }

        @Override
        public void active(final CoreModule coreModule) {
        }

        @Override
        public void frozen(final CoreModule coreModule, final boolean isIgnoreModuleException) {
        }

        @Override
        public Collection<CoreModule> list() {
            return Collections.singletonList(module);
        }

        @Override
        public CoreModule get(final String uniqueId) {
            return module.getUniqueId().equals(uniqueId) ? module : null;
        }

        @Override
        public CoreModule getThrowsExceptionIfNull(final String uniqueId) throws ModuleException {
            final CoreModule found = get(uniqueId);
            if (null == found) {
                throw new ModuleException(uniqueId, ModuleException.ErrorCode.MODULE_NOT_EXISTED);
            }
            return found;
        }

        @Override
        public CoreModule unload(final CoreModule coreModule, final boolean isIgnoreModuleException) {
            return coreModule;
        }

        @Override
        public void unloadAll() {
        }
    }
}
