package com.alibaba.jvm.sandbox.qatest.api;

import com.alibaba.jvm.sandbox.api.listener.ext.Behavior;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class BehaviorTestCase {

    @Test
    public void test$behavior$method() throws NoSuchMethodException {
        final Method method = String.class.getMethod("toString");
        final Behavior behavior = new Behavior.MethodImpl(method);
        Assertions.assertEquals(method, behavior.getTarget());
        Assertions.assertEquals(method.getName(), behavior.getName());
        Assertions.assertEquals(method.isAccessible(), behavior.isAccessible());
        {
            final boolean access = behavior.isAccessible();
            try {
                behavior.setAccessible(!access);
                Assertions.assertEquals(!access, behavior.isAccessible());
                Assertions.assertEquals(!access, method.isAccessible());
            } finally {
                behavior.setAccessible(access);
                Assertions.assertEquals(access, behavior.isAccessible());
                Assertions.assertEquals(access, method.isAccessible());
            }
        }
        Assertions.assertEquals(method.getModifiers(), behavior.getModifiers());
        Assertions.assertEquals(method.getDeclaringClass(), behavior.getDeclaringClass());
        Assertions.assertEquals(method.getReturnType(), behavior.getReturnType());
        Assertions.assertArrayEquals(method.getParameterTypes(), behavior.getParameterTypes());
        Assertions.assertArrayEquals(method.getExceptionTypes(), behavior.getExceptionTypes());
        Assertions.assertArrayEquals(method.getAnnotations(), behavior.getAnnotations());
        Assertions.assertArrayEquals(method.getDeclaredAnnotations(), behavior.getDeclaredAnnotations());
    }

    @Test
    public void test$behavior$constructor() throws NoSuchMethodException {
        final Constructor<?> constructor = String.class.getConstructor(String.class);
        final Behavior behavior = new Behavior.ConstructorImpl(constructor);
        Assertions.assertEquals(constructor, behavior.getTarget());
        Assertions.assertEquals("<init>", behavior.getName());
        Assertions.assertEquals(constructor.isAccessible(), behavior.isAccessible());
        {
            final boolean access = behavior.isAccessible();
            try {
                behavior.setAccessible(!access);
                Assertions.assertEquals(!access, behavior.isAccessible());
                Assertions.assertEquals(!access, constructor.isAccessible());
            } finally {
                behavior.setAccessible(access);
                Assertions.assertEquals(access, behavior.isAccessible());
                Assertions.assertEquals(access, constructor.isAccessible());
            }
        }
        Assertions.assertEquals(constructor.getModifiers(), behavior.getModifiers());
        Assertions.assertEquals(constructor.getDeclaringClass(), behavior.getDeclaringClass());
        Assertions.assertEquals(constructor.getDeclaringClass(), behavior.getReturnType());
        Assertions.assertArrayEquals(constructor.getParameterTypes(), behavior.getParameterTypes());
        Assertions.assertArrayEquals(constructor.getExceptionTypes(), behavior.getExceptionTypes());
        Assertions.assertArrayEquals(constructor.getAnnotations(), behavior.getAnnotations());
        Assertions.assertArrayEquals(constructor.getDeclaredAnnotations(), behavior.getDeclaredAnnotations());
    }

}
