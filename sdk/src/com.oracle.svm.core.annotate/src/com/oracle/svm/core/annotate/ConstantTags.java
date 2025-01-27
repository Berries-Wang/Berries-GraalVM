package com.oracle.svm.core.annotate;

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
}
