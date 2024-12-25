package com.oracle.svm.hosted.strictreflection;

import com.oracle.svm.hosted.strictreflection.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantValueAnalyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

class ConstantCallHandlers {

    public static BiFunction<AnalyzerSuite, CallContext, Object> get(String encodedMethod) {
        return reflectiveCallHandlers.get(encodedMethod);
    }

    private static final Map<String, BiFunction<AnalyzerSuite, CallContext, Object>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantCallHandlers::classAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), ConstantCallHandlers::fieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), ConstantCallHandlers::declaredFieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), ConstantCallHandlers::constructorAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), ConstantCallHandlers::declaredConstructorAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), ConstantCallHandlers::methodAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), ConstantCallHandlers::declaredMethodAccessHandler);
        }
    };

    private static Object classAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 0);

        if (className.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerClassLookup(className.get());
        try {
            Class<?> clazz = Class.forName(className.get(), false, ClassLoader.getSystemClassLoader());
            callContext.constantCalls().put(callContext.callSite(), clazz);
            return clazz;
        } catch (ClassNotFoundException e) {
            // The call will throw an exception during runtime - ignore for the rest of the analysis.
            return e;
        }
    }

    private static Object fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 1);

        if (clazz.isEmpty() || fieldName.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            Field field = declaredOnly
                    ? clazz.get().getDeclaredField(fieldName.get())
                    : clazz.get().getField(fieldName.get());
            callContext.constantCalls().put(callContext.callSite(), field);
            return field;
        } catch (NoSuchFieldException e) {
            // The call will throw an exception during runtime - ignore for the rest of the analysis.
            return e;
        }
    }

    private static Object fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return fieldAccessHandler(analyzerSuite, callContext, false);
    }

    private static Object declaredFieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return fieldAccessHandler(analyzerSuite, callContext, true);
    }

    private static Object constructorAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<ArrayList<Class<?>>> paramTypes = inferConstant(analyzerSuite.classArrayAnalyzer(), callContext, 1);

        if (clazz.isEmpty() || paramTypes.isEmpty()) {
            return null;
        }

        Class<?>[] paramTypesArr = paramTypes.get().toArray(Class[]::new);
        RuntimeReflection.registerConstructorLookup(clazz.get(), paramTypesArr);
        try {
            Constructor<?> constructor = declaredOnly
                    ? clazz.get().getDeclaredConstructor(paramTypesArr)
                    : clazz.get().getConstructor(paramTypesArr);
            callContext.constantCalls().put(callContext.callSite(), constructor);
            return constructor;
        } catch (NoSuchMethodException e) {
            // The call will throw an exception during runtime - ignore for the rest of the analysis.
            return e;
        }
    }

    private static Object constructorAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return constructorAccessHandler(analyzerSuite, callContext, false);
    }

    private static Object declaredConstructorAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return constructorAccessHandler(analyzerSuite, callContext, true);
    }

    private static Object methodAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<String> methodName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 1);
        Optional<ArrayList<Class<?>>> paramTypes = inferConstant(analyzerSuite.classArrayAnalyzer(), callContext, 2);

        if (clazz.isEmpty() || methodName.isEmpty() || paramTypes.isEmpty()) {
            return null;
        }

        Class<?>[] paramTypesArr = paramTypes.get().toArray(Class[]::new);
        RuntimeReflection.registerMethodLookup(clazz.get(), methodName.get(), paramTypesArr);
        try {
            Method method = declaredOnly
                    ? clazz.get().getDeclaredMethod(methodName.get(), paramTypesArr)
                    : clazz.get().getMethod(methodName.get(), paramTypesArr);
            callContext.constantCalls().put(callContext.callSite(), method);
            return method;
        } catch (NoSuchMethodException e) {
            // The call will throw an exception during runtime - ignore for the rest of the analysis.
            return e;
        }
    }

    private static Object methodAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return methodAccessHandler(analyzerSuite, callContext, false);
    }

    private static Object declaredMethodAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return methodAccessHandler(analyzerSuite, callContext, true);
    }

    private static <T> Optional<T> inferConstant(ConstantValueAnalyzer<T> analyzer, CallContext callContext, int argumentIndex) {
        return analyzer.inferConstant(getCallArg(callContext, argumentIndex), callContext.constantCalls());
    }

    private static <T> Optional<ArrayList<T>> inferConstant(ConstantArrayAnalyzer<T> analyzer, CallContext callContext, int argumentIndex) {
        return analyzer.inferConstant(getCallArg(callContext, argumentIndex), callContext.callSite(), callContext.constantCalls());
    }

    private static SourceValue getCallArg(CallContext callContext, int argumentIndex) {
        return Utils.getCallArg(callContext.callSite(), argumentIndex, callContext.frame());
    }
}
