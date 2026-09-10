package com.alibaba.jvm.sandbox.core.util;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Sandbox的命名空间注册到logback
 */
public class NamespaceConvert extends ClassicConverter {

    private static volatile String namespace;

    @Override
    public String convert(ILoggingEvent event) {
        return null == namespace
                ? "NULL"
                : namespace;
    }

    /**
     * 注册命名空间到Logback
     *
     * @param namespace 命名空间
     */
    public static void initNamespaceConvert(final String namespace) {
        NamespaceConvert.namespace = namespace;
        /*
         * DEPENDENCY-UPGRADE FIX (Logback 1.2.x -> 1.6.x): Logback replaced the legacy String-based
         * defaultConverterMap with a Supplier-based registry. Register the converter through the supported
         * 1.6 API so the existing %SANDBOX_NAMESPACE pattern keeps the same observable behavior.
         */
        PatternLayout.DEFAULT_CONVERTER_SUPPLIER_MAP.put("SANDBOX_NAMESPACE", NamespaceConvert::new);
    }

}
