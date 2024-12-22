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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
                BiPredicate<AnalyzerSuite, CallContext> handler = ConstantCallHandlers.get(Utils.encodeMethodCall(methodCall));
                if (handler == null) {
                    continue;
                }
                callRegistered |= handler.test(analyzerSuite, new CallContext(frames[i], methodCall, constantCalls));
            }
        }

        return callRegistered;
    }
}

record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantClassAnalyzer classAnalyzer, ConstantArrayAnalyzer<Class<?>> classArrayAnalyzer) {

}

record CallContext(Frame<SourceValue> frame, MethodInsnNode callSite, Map<MethodInsnNode, Object> constantCalls) {

}
