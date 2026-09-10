package com.alibaba.jvm.sandbox.module.debug.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * OGNL dependency-upgrade regression tests.
 *
 * <p>OGNL 3.4.x removed the legacy production {@code DefaultMemberAccess} implementation and
 * hardened reflective property access. These tests keep the supported watch-expression path green
 * while also preventing future dependency upgrades from silently restoring unrestricted private
 * field access.</p>
 */
public class ExpressTest {

    @Test
    void shouldEvaluatePublicBeanPropertyAndBoundVariableAfterOgnlUpgrade() throws Exception {
        final Express express = Express.ExpressFactory
                .newExpress(new WatchTarget("public-value", "private-value"))
                .bind("suffix", "-bound");

        assertEquals("public-value-bound", express.get("value + #suffix"));
    }

    @Test
    void shouldRejectDirectPrivateFieldAccessUnderSecurityBaseline() {
        final Express express = Express.ExpressFactory
                .newExpress(new WatchTarget("public-value", "private-value"));

        assertThrows(Express.ExpressException.class, () -> express.get("hidden"));
    }

    public static final class WatchTarget {
        private final String value;
        private final String hidden;

        private WatchTarget(final String value, final String hidden) {
            this.value = value;
            this.hidden = hidden;
        }

        public String getValue() {
            return value;
        }
    }
}
