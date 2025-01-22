package com.oracle.svm.hosted.strictreflection;

import com.oracle.svm.hosted.strictreflection.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantValueAnalyzer;
import com.oracle.svm.util.ReflectionUtil;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

class ConstantCallHandlers {

    public static BiFunction<AnalyzerSuite, CallContext, Object> get(String encodedMethod) {
        return reflectiveCallHandlers.get(encodedMethod);
    }

    private static final Constructor<MethodHandles.Lookup> lookupConstructor = ReflectionUtil.lookupConstructor(MethodHandles.Lookup.class, Class.class);

    private static final Map<String, BiFunction<AnalyzerSuite, CallContext, Object>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantCallHandlers::classAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"), ConstantCallHandlers::classAccessWithInitializeHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), ConstantCallHandlers::fieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), ConstantCallHandlers::declaredFieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), ConstantCallHandlers::constructorAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), ConstantCallHandlers::declaredConstructorAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), ConstantCallHandlers::methodAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), ConstantCallHandlers::declaredMethodAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getFields", "()[Ljava/lang/reflect/Field;"), ConstantCallHandlers::allFieldsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;"), ConstantCallHandlers::allDeclaredFieldsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getConstructors", "()[Ljava/lang/reflect/Constructor;"), ConstantCallHandlers::allConstructorsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;"), ConstantCallHandlers::allDeclaredConstructorsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getMethods", "()[Ljava/lang/reflect/Method;"), ConstantCallHandlers::allMethodsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredMethods", "()[Ljava/lang/reflect/Method;"), ConstantCallHandlers::allDeclaredMethodsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getClasses", "()[Ljava/lang/Class;"), ConstantCallHandlers::allClassesHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredClasses", "()[Ljava/lang/Class;"), ConstantCallHandlers::allDeclaredClassesHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getNestMembers", "()[Ljava/lang/Class;"), ConstantCallHandlers::allNestMembersHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getPermittedSubclasses", "()[Ljava/lang/Class;"), ConstantCallHandlers::allPermittedSubclassesHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getRecordComponents", "()[Ljava/lang/reflect/RecordComponent;"), ConstantCallHandlers::allRecordComponentsHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getSigners", "()[Ljava/lang/Object;"), ConstantCallHandlers::allSignersHandler);

            put(Utils.encodeMethodCall("java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantCallHandlers::classAccessHandlerFromClassLoader);

            put(Utils.encodeMethodCall("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;"), ConstantCallHandlers::methodTypeOneHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;"), ConstantCallHandlers::methodTypeTwoHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;"), ConstantCallHandlers::methodTypeThreeHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;"), ConstantCallHandlers::methodTypeFourHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodType", "methodType", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodType;"), ConstantCallHandlers::methodTypeFiveHandler);

            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles", "lookup", "()Ljava/lang/invoke/MethodHandles$Lookup;"), ConstantCallHandlers::lookupHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles", "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;"), ConstantCallHandlers::privateLookupInHandler);

            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findClass", "(Ljava/lang/String;)Ljava/lang/Class;"), ConstantCallHandlers::findClassHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findConstructor", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findConstructorHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findStaticHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findStaticGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findStaticGetterHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findStaticSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findStaticSetterHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findStaticVarHandle", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"), ConstantCallHandlers::findStaticVarHandleHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findVirtualHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findGetterHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;"), ConstantCallHandlers::findSetterHandler);
            put(Utils.encodeMethodCall("java/lang/invoke/MethodHandles$Lookup", "findVarHandle", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;"), ConstantCallHandlers::findVarHandleHandler);
        }
    };

    private static Object classAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean withInitializeParam) {
        Optional<String> className = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 0);

        if (className.isEmpty()) {
            return null;
        }

        if (withInitializeParam && inferConstant(analyzerSuite.booleanAnalyzer(), callContext, 1).isEmpty()) {
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

    private static Object classAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return classAccessHandler(analyzerSuite, callContext, false);
    }

    private static Object classAccessWithInitializeHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return classAccessHandler(analyzerSuite, callContext, true);
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

    private static Object bulkAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, Consumer<Class<?>> registrationMethod, Function<Class<?>, Object> accessMethod) {
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);

        if (clazz.isEmpty()) {
            return null;
        }

        registrationMethod.accept(clazz.get());
        return accessMethod.apply(clazz.get());
    }

    private static Object allFieldsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllFields, Class::getFields);
    }

    private static Object allDeclaredFieldsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllDeclaredFields, Class::getDeclaredFields);
    }

    private static Object allConstructorsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllConstructors, Class::getConstructors);
    }

    private static Object allDeclaredConstructorsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllDeclaredConstructors, Class::getDeclaredConstructors);
    }

    private static Object allMethodsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllMethods, Class::getMethods);
    }

    private static Object allDeclaredMethodsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllDeclaredMethods, Class::getDeclaredMethods);
    }

    private static Object allClassesHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllClasses, Class::getClasses);
    }

    private static Object allDeclaredClassesHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllDeclaredClasses, Class::getDeclaredClasses);
    }

    private static Object allNestMembersHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllNestMembers, Class::getNestMembers);
    }

    private static Object allPermittedSubclassesHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllPermittedSubclasses, Class::getPermittedSubclasses);
    }

    private static Object allRecordComponentsHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllRecordComponents, Class::getRecordComponents);
    }

    private static Object allSignersHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        return bulkAccessHandler(analyzerSuite, callContext, RuntimeReflection::registerAllSigners, Class::getSigners);
    }

    private static Object classAccessHandlerFromClassLoader(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 1);

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

    private static Object methodTypeOneHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> rtype = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);

        if (rtype.isEmpty()) {
            return null;
        }

        MethodType methodType = MethodType.methodType(rtype.get());
        callContext.constantCalls().put(callContext.callSite(), methodType);
        return methodType;
    }

    private static Object methodTypeTwoHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> rtype = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<Class<?>> ptype0 = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);

        if (rtype.isEmpty() || ptype0.isEmpty()) {
            return null;
        }

        MethodType methodType = MethodType.methodType(rtype.get(), ptype0.get());
        callContext.constantCalls().put(callContext.callSite(), methodType);
        return methodType;
    }

    private static Object methodTypeThreeHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> rtype = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<ArrayList<Class<?>>> ptypes = inferConstant(analyzerSuite.classArrayAnalyzer(), callContext, 1);

        if (rtype.isEmpty() || ptypes.isEmpty()) {
            return null;
        }

        MethodType methodType = MethodType.methodType(rtype.get(), ptypes.get().toArray(Class[]::new));
        callContext.constantCalls().put(callContext.callSite(), methodType);
        return methodType;
    }

    private static Object methodTypeFourHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> rtype = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<Class<?>> ptype0 = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<ArrayList<Class<?>>> ptypes = inferConstant(analyzerSuite.classArrayAnalyzer(), callContext, 2);

        if (rtype.isEmpty() || ptype0.isEmpty() || ptypes.isEmpty()) {
            return null;
        }

        MethodType methodType = MethodType.methodType(rtype.get(), ptype0.get(), ptypes.get().toArray(Class[]::new));
        callContext.constantCalls().put(callContext.callSite(), methodType);
        return methodType;
    }

    private static Object methodTypeFiveHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> rtype = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<MethodType> ptypes = inferConstant(analyzerSuite.methodTypeAnalyzer(), callContext, 1);

        if (rtype.isEmpty() || ptypes.isEmpty()) {
            return null;
        }

        MethodType methodType = MethodType.methodType(rtype.get(), ptypes.get());
        callContext.constantCalls().put(callContext.callSite(), methodType);
        return methodType;
    }

    private static Object lookupHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Class<?> callerClass = callContext.caller().getDeclaringClass().getJavaClass();
        try {
            MethodHandles.Lookup lookup = lookupConstructor.newInstance(callerClass);
            callContext.constantCalls().put(callContext.callSite(), lookup);
            return lookup;
        } catch (Exception e) {
            return e;
        }
    }

    private static Object privateLookupInHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<Class<?>> targetClass = inferConstant(analyzerSuite.classAnalyzer(), callContext, 0);
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 1);

        if (targetClass.isEmpty() || lookup.isEmpty()) {
            return null;
        }

        try {
            MethodHandles.Lookup lookupTarget = MethodHandles.privateLookupIn(targetClass.get(), lookup.get());
            callContext.constantCalls().put(callContext.callSite(), lookupTarget);
            return lookupTarget;
        } catch (IllegalAccessException e) {
            return e;
        }
    }

    private static Object findClassHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<String> className = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 1);

        if (lookup.isEmpty() || className.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerClassLookup(className.get());
        try {
            return lookup.get().findClass(className.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findConstructorHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<MethodType> methodType = inferConstant(analyzerSuite.methodTypeAnalyzer(), callContext, 2);

        if (lookup.isEmpty() || clazz.isEmpty() || methodType.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerConstructorLookup(clazz.get(), methodType.get().parameterArray());
        try {
            return lookup.get().findConstructor(clazz.get(), methodType.get());
        } catch (Exception e) {
            return e;
        }

    }

    private static Object findStaticHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> methodName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<MethodType> methodType = inferConstant(analyzerSuite.methodTypeAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || methodName.isEmpty() || methodType.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerMethodLookup(clazz.get(), methodName.get(), methodType.get().parameterArray());
        try {
            return lookup.get().findStatic(clazz.get(), methodName.get(), methodType.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findStaticGetterHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<Class<?>> type = inferConstant(analyzerSuite.classAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || fieldName.isEmpty() || type.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            return lookup.get().findStaticGetter(clazz.get(), fieldName.get(), type.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findStaticSetterHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<Class<?>> type = inferConstant(analyzerSuite.classAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || fieldName.isEmpty() || type.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            return lookup.get().findStaticSetter(clazz.get(), fieldName.get(), type.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findStaticVarHandleHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<Class<?>> type = inferConstant(analyzerSuite.classAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || fieldName.isEmpty() || type.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            return lookup.get().findStaticVarHandle(clazz.get(), fieldName.get(), type.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findVirtualHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> methodName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<MethodType> methodType = inferConstant(analyzerSuite.methodTypeAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || methodName.isEmpty() || methodType.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerMethodLookup(clazz.get(), methodName.get(), methodType.get().parameterArray());
        try {
            return lookup.get().findVirtual(clazz.get(), methodName.get(), methodType.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findGetterHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<Class<?>> type = inferConstant(analyzerSuite.classAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || fieldName.isEmpty() || type.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            return lookup.get().findGetter(clazz.get(), fieldName.get(), type.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findSetterHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<Class<?>> type = inferConstant(analyzerSuite.classAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || fieldName.isEmpty() || type.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            return lookup.get().findSetter(clazz.get(), fieldName.get(), type.get());
        } catch (Exception e) {
            return e;
        }
    }

    private static Object findVarHandleHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<MethodHandles.Lookup> lookup = inferConstant(analyzerSuite.methodHandlesLookupAnalyzer(), callContext, 0);
        Optional<Class<?>> clazz = inferConstant(analyzerSuite.classAnalyzer(), callContext, 1);
        Optional<String> fieldName = inferConstant(analyzerSuite.stringAnalyzer(), callContext, 2);
        Optional<Class<?>> type = inferConstant(analyzerSuite.classAnalyzer(), callContext, 3);

        if (lookup.isEmpty() || clazz.isEmpty() || fieldName.isEmpty() || type.isEmpty()) {
            return null;
        }

        RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
        try {
            return lookup.get().findVarHandle(clazz.get(), fieldName.get(), type.get());
        } catch (Exception e) {
            return e;
        }
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
