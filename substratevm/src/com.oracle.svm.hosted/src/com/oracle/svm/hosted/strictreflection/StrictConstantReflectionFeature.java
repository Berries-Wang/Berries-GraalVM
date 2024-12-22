package com.oracle.svm.hosted.strictreflection;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantClassAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantStringAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ControlFlowGraphAnalyzer;
import com.oracle.svm.util.ClassUtil;
import jdk.graal.compiler.options.Option;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceInterpreter;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

@AutomaticallyRegisteredFeature
public class StrictConstantReflectionFeature implements InternalFeature {

    public static class Options {
        @Option(help = "Enable an optimization independent constant reflection analysis.")
        public static final HostedOptionKey<Boolean> StrictConstantReflection = new HostedOptionKey<>(false);
    }

    private static final Set<AnalysisMethod> analyzedMethods = ConcurrentHashMap.newKeySet();
    private static final Map<AnalysisType, ClassNode> bytecodeCache = new ConcurrentHashMap<>();

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        if (!Options.StrictConstantReflection.getValue()) {
            return;
        }

        AnalysisUniverse universe = ((FeatureImpl.DuringAnalysisAccessImpl) access).getUniverse();

        List<AnalysisMethod> methodsToAnalyze = universe.getMethods().stream()
                .filter(m -> !analyzedMethods.contains(m))
                .filter(AnalysisMethod::isReachable)
                .filter(m -> !m.isInBaseLayer())
                .toList();
        analyzedMethods.addAll(methodsToAnalyze);

        boolean newReflectionRegistration = false;
        for (AnalysisMethod method : methodsToAnalyze) {
            ClassNode classNode = getClassBytecode(method.getDeclaringClass());
            if (classNode == null) {
                continue;
            }

            MethodNode methodNode = findMethodNode(method, classNode.methods);
            if (methodNode == null) {
                continue;
            }

            newReflectionRegistration |= analyzeMethod(methodNode, classNode);
        }

        if (newReflectionRegistration) {
            access.requireAnalysisIteration();
        }
    }

    private ClassNode getClassBytecode(AnalysisType clazz) {
        if (bytecodeCache.containsKey(clazz)) {
            return bytecodeCache.get(clazz);
        }

        Class<?> javaClass = clazz.getJavaClass();
        String classFile = ClassUtil.getUnqualifiedName(javaClass) + ".class";
        try (InputStream cfStream = javaClass.getResourceAsStream(classFile)) {
            if (cfStream == null) {
                return null;
            }
            ClassNode classNode = new ClassNode();
            ClassReader reader = new ClassReader(cfStream.readAllBytes());
            reader.accept(classNode, 0);
            bytecodeCache.put(clazz, classNode);
            return classNode;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    MethodNode findMethodNode(AnalysisMethod method, List<MethodNode> methodNodes) {
        for (MethodNode methodNode : methodNodes) {
            if (methodNode.name.equals(method.getName()) && methodNode.desc.equals(method.getSignature().toString())) {
                return methodNode;
            }
        }
        return null;
    }

    private boolean analyzeMethod(MethodNode methodNode, ClassNode contextClassNode) {
        Analyzer<SourceValue> analyzer = new ControlFlowGraphAnalyzer<>(new SourceInterpreter());
        try {
            analyzer.analyze(contextClassNode.name, methodNode);
        } catch (AnalyzerException e) {
            throw new RuntimeException(e);
        }

        AbstractInsnNode[] instructions = methodNode.instructions.toArray();

        @SuppressWarnings("unchecked")
        ControlFlowGraphNode<SourceValue>[] frames = Arrays.stream(analyzer.getFrames())
                .map(frame -> (ControlFlowGraphNode<SourceValue>) frame)
                .toArray(ControlFlowGraphNode[]::new);

        AnalyzerSuite analyzerSuite = new AnalyzerSuite(
                new ConstantStringAnalyzer(instructions, frames),
                new ConstantClassAnalyzer(instructions, frames),
                new ConstantArrayAnalyzer<>(instructions, frames, new ConstantClassAnalyzer(instructions, frames))
        );
        Map<MethodInsnNode, Object> constantCalls = new HashMap<>();

        boolean callRegistered = false;
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                BiPredicate<AnalyzerSuite, CallContext> handler = reflectiveCallHandlers.get(Utils.encodeMethodCall(methodCall));
                if (handler == null) {
                    continue;
                }
                callRegistered |= handler.test(analyzerSuite, new CallContext(frames[i], methodCall, constantCalls));
            }
        }

        return callRegistered;
    }

    private record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantClassAnalyzer classAnalyzer, ConstantArrayAnalyzer<Class<?>> classArrayAnalyzer) {

    }

    private record CallContext(Frame<SourceValue> frame, MethodInsnNode callSite, Map<MethodInsnNode, Object> constantCalls) {

    }

    private static SourceValue getCallArg(CallContext callContext, int argumentIndex) {
        return Utils.getCallArg(callContext.callSite, argumentIndex, callContext.frame);
    }

    private static final Map<String, BiPredicate<AnalyzerSuite, CallContext>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), StrictConstantReflectionFeature::classAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), StrictConstantReflectionFeature::fieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), StrictConstantReflectionFeature::declaredFieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), StrictConstantReflectionFeature::constructorAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"), StrictConstantReflectionFeature::declaredConstructorAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), StrictConstantReflectionFeature::methodAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"), StrictConstantReflectionFeature::declaredMethodAccessHandler);
        }
    };

    private static boolean classAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 0), callContext.constantCalls);
        if (className.isPresent()) {
            RuntimeReflection.registerClassLookup(className.get());
            try {
                callContext.constantCalls.put(callContext.callSite, Class.forName(className.get(), false, ClassLoader.getSystemClassLoader()));
            } catch (ClassNotFoundException e) {
                // The call will throw an exception during runtime - ignore for the rest of the analysis.
            }
            return true;
        } else {
            return false;
        }
    }

    private static boolean fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer.inferConstant(getCallArg(callContext, 0), callContext.constantCalls);
        Optional<String> fieldName = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 1), callContext.constantCalls);
        if (clazz.isPresent() && fieldName.isPresent()) {
            RuntimeReflection.registerFieldLookup(clazz.get(), fieldName.get());
            try {
                Field field = declaredOnly ? clazz.get().getDeclaredField(fieldName.get()) : clazz.get().getField(fieldName.get());
                callContext.constantCalls.put(callContext.callSite, field);
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
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer.inferConstant(getCallArg(callContext, 0), callContext.constantCalls);
        Optional<ArrayList<Class<?>>> paramTypes = analyzerSuite.classArrayAnalyzer.inferConstant(getCallArg(callContext, 0), callContext.callSite, callContext.constantCalls);
        if (clazz.isPresent() && paramTypes.isPresent()) {
            Class<?>[] paramTypesArr = paramTypes.get().toArray(Class[]::new);
            RuntimeReflection.registerConstructorLookup(clazz.get(), paramTypesArr);
            try {
                Constructor<?> constructor = declaredOnly ? clazz.get().getDeclaredConstructor(paramTypesArr) : clazz.get().getConstructor(paramTypesArr);
                callContext.constantCalls.put(callContext.callSite, constructor);
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
        Optional<Class<?>> clazz = analyzerSuite.classAnalyzer.inferConstant(getCallArg(callContext, 0), callContext.constantCalls);
        Optional<String> methodName = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 1), callContext.constantCalls);
        Optional<ArrayList<Class<?>>> paramTypes = analyzerSuite.classArrayAnalyzer.inferConstant(getCallArg(callContext, 2), callContext.callSite, callContext.constantCalls);
        if (clazz.isPresent() && methodName.isPresent() && paramTypes.isPresent()) {
            Class<?>[] paramTypesArr = paramTypes.get().toArray(Class[]::new);
            RuntimeReflection.registerMethodLookup(clazz.get(), methodName.get(), paramTypesArr);
            try {
                Method method = declaredOnly ? clazz.get().getDeclaredMethod(methodName.get(), paramTypesArr) : clazz.get().getMethod(methodName.get(), paramTypesArr);
                callContext.constantCalls.put(callContext.callSite, method);
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
}
