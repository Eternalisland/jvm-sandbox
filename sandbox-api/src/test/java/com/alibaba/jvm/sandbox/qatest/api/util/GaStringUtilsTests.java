package com.alibaba.jvm.sandbox.qatest.api.util;

import com.alibaba.jvm.sandbox.api.util.GaStringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class GaStringUtilsTests {

    @Test
    public void testGetJavaClassName() {
        Assertions.assertEquals("java.lang.String",
                GaStringUtils.getJavaClassName(String.class));
    }

    @Test
    public void testGetJavaClassNameArray() {
        Assertions.assertNull(GaStringUtils.getJavaClassNameArray(null));
        Assertions.assertNull(GaStringUtils.getJavaClassNameArray(new Class[]{}));

        Class[] classes = new Class[]{String.class, Integer.class};
        String[] strings =
                new String[]{"java.lang.String", "java.lang.Integer"};

        Assertions.assertArrayEquals(strings,
                GaStringUtils.getJavaClassNameArray(classes));
    }

    @Test
    public void testIsEmpty() {
        Assertions.assertTrue(GaStringUtils.isEmpty(""));
        Assertions.assertTrue(GaStringUtils.isEmpty(null));

        Assertions.assertFalse(GaStringUtils.isEmpty("foo"));
    }

    @Test
    public void testMatching() {
        Assertions.assertFalse(GaStringUtils.matching(null, "bar"));
        Assertions.assertFalse(GaStringUtils.matching("foo", null));
        Assertions.assertFalse(GaStringUtils.matching(null, null));
        Assertions.assertFalse(GaStringUtils.matching("foo", "bar"));
        Assertions.assertFalse(GaStringUtils.matching("foobar", "foo"));
        Assertions.assertFalse(GaStringUtils.matching("foobar", "*a"));
        Assertions.assertFalse(GaStringUtils.matching("foo", "\\o"));
        Assertions.assertFalse(GaStringUtils.matching("foo", "\\*"));
        Assertions.assertFalse(GaStringUtils.matching("foo", "f\\?o"));
        Assertions.assertFalse(GaStringUtils.matching("fooMatching", "fool\\*ing"));

        Assertions.assertTrue(GaStringUtils.matching("foo", "*"));
        Assertions.assertTrue(GaStringUtils.matching("foo", "?oo"));
        Assertions.assertTrue(GaStringUtils.matching("foo", "**o"));
        Assertions.assertTrue(GaStringUtils.matching("foo", "f?o"));
        Assertions.assertTrue(GaStringUtils.matching("fooMatching", "foo*"));
        Assertions.assertTrue(GaStringUtils.matching("fooMatching", "foo*ing"));
    }
}
