package com.oracle.svm.core.annotate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Used for representing constant reflection calls caught by {@link com.oracle.svm.reflectionagent}.
 * <p>
 * Due to build-time initialization, the calls must implement the equivalent logic of their corresponding
 * reflection method. Since these calls will be folded into constants, they will never be executed during
 * image run-time.
 */
public final class ConstantTags {

    private static final StackWalker stackWalker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    public static Class<?> forName(String className) throws ClassNotFoundException {
        return Class.forName(className, true, stackWalker.getCallerClass().getClassLoader());
    }

    public static Class<?> forName(String className, boolean initialize, ClassLoader classLoader) throws ClassNotFoundException {
        return Class.forName(className, initialize, classLoader);
    }

    public static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        return clazz.getField(fieldName);
    }

    public static Field getDeclaredField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        return clazz.getDeclaredField(fieldName);
    }

    public static Constructor<?> getConstructor(Class<?> clazz, Class<?>... parameterTypes) throws NoSuchMethodException {
        return clazz.getConstructor(parameterTypes);
    }

    public static Constructor<?> getDeclaredConstructor(Class<?> clazz, Class<?>... parameterTypes) throws NoSuchMethodException {
        return clazz.getDeclaredConstructor(parameterTypes);
    }

    public static Method getMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return clazz.getMethod(methodName, parameterTypes);
    }

    public static Method getDeclaredMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        return clazz.getDeclaredMethod(methodName, parameterTypes);
    }
}
