package com.oracle.svm.hosted.strictreflection;

import com.oracle.svm.hosted.strictreflection.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantClassAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantStringAnalyzer;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

class ConstantCallHandlers {

    public static BiPredicate<AnalyzerSuite, CallContext> get(String encodedMethod) {
        return reflectiveCallHandlers.get(encodedMethod);
    }

    private static final Map<String, BiPredicate<AnalyzerSuite, CallContext>> reflectiveCallHandlers = new HashMap<>() {
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

    private static boolean classAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = analyzerSuite.stringAnalyzer().inferConstant(getCallArg(callContext, 0), callContext.constantCalls());
        if (className.isPresent()) {
            RuntimeReflection.registerClassLookup(className.get());
            try {
                callContext.constantCalls().put(callContext.callSite(), Class.forName(className.get(), false, ClassLoader.getSystemClassLoader()));
            } catch (ClassNotFoundException e) {
                // The call will throw an exception during runtime - ignore for the rest of the analysis.
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer().inferConstant(getCallArg(callContext, 0), callContext.constantCalls());
        Optional<String> fieldName = analyzerSuite.stringAnalyzer().inferConstant(getCallArg(callContext, 1), callContext.constantCalls());
        if (clazz.isPresent() && fieldName.isPresent()) {
            RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
            try {
                Field field = declaredOnly ? clazz.get().getDeclaredField(fieldName.get()) : clazz.get().getField(fieldName.get());
                callContext.constantCalls().put(callContext.callSite(), field);
            } catch (NoSuchFieldException e) {
                // The call will throw an exception during runtime - ignore for the rest of the analysis.
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return fieldAccessHandler(analyzerSuite, callContext, false);
    }

    private static boolean declaredFieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return fieldAccessHandler(analyzerSuite, callContext, true);
    }

    private static boolean constructorAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer().inferConstant(getCallArg(callContext, 0), callContext.constantCalls());
        Optional<ArrayList<Class<?>>> paramTypes = analyzerSuite.classArrayAnalyzer().inferConstant(getCallArg(callContext, 0), callContext.callSite(), callContext.constantCalls());
        if (clazz.isPresent() && paramTypes.isPresent()) {
            Class<?>[] paramTypesArr = paramTypes.get().toArray(Class[]::new);
            RuntimeReflection.registerConstructorLookup(clazz.get(), paramTypesArr);
            try {
                Constructor<?> constructor = declaredOnly ? clazz.get().getDeclaredConstructor(paramTypesArr) : clazz.get().getConstructor(paramTypesArr);
                callContext.constantCalls().put(callContext.callSite(), constructor);
            } catch (NoSuchMethodException e) {
                // The call will throw an exception during runtime - ignore for the rest of the analysis.
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean constructorAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return constructorAccessHandler(analyzerSuite, callContext, false);
    }

    private static boolean declaredConstructorAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return constructorAccessHandler(analyzerSuite, callContext, true);
    }

    private static boolean methodAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer().inferConstant(getCallArg(callContext, 0), callContext.constantCalls());
        Optional<String> methodName = analyzerSuite.stringAnalyzer().inferConstant(getCallArg(callContext, 1), callContext.constantCalls());
        Optional<ArrayList<Class<?>>> paramTypes = analyzerSuite.classArrayAnalyzer().inferConstant(getCallArg(callContext, 2), callContext.callSite(), callContext.constantCalls());
        if (clazz.isPresent() && methodName.isPresent() && paramTypes.isPresent()) {
            Class<?>[] paramTypesArr = paramTypes.get().toArray(Class[]::new);
            RuntimeReflection.registerMethodLookup(clazz.get(), methodName.get(), paramTypesArr);
            try {
                Method method = declaredOnly ? clazz.get().getDeclaredMethod(methodName.get(), paramTypesArr) : clazz.get().getMethod(methodName.get(), paramTypesArr);
                callContext.constantCalls().put(callContext.callSite(), method);
            } catch (NoSuchMethodException e) {
                // The call will throw an exception during runtime - ignore for the rest of the analysis.
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean methodAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return methodAccessHandler(analyzerSuite, callContext, false);
    }

    private static boolean declaredMethodAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return methodAccessHandler(analyzerSuite, callContext, true);
    }

    private static SourceValue getCallArg(CallContext callContext, int argumentIndex) {
        return Utils.getCallArg(callContext.callSite(), argumentIndex, callContext.frame());
    }
}
