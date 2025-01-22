package com.oracle.svm.hosted.strictreflection;

import com.oracle.graal.pointsto.meta.AnalysisMethod;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.meta.AnalysisUniverse;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.FeatureImpl;
import com.oracle.svm.hosted.ImageClassLoader;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantBooleanAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantClassAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantMethodHandlesLookupAnalyzer;
import com.oracle.svm.hosted.strictreflection.analyzers.ConstantMethodTypeAnalyzer;
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
import java.util.function.BiFunction;

@AutomaticallyRegisteredFeature
public class StrictConstantReflectionFeature implements InternalFeature {

    public static class Options {
        @Option(help = "Enable an optimization independent constant reflection analysis.")
        static final HostedOptionKey<Boolean> StrictConstantReflection = new HostedOptionKey<>(false);

        @Option(help = "Specify the log location for reflection calls registered by the strict constant reflection feature.")
        static final HostedOptionKey<String> StrictConstantReflectionLog = new HostedOptionKey<>(null);
    }

    private static final Set<AnalysisMethod> analyzedMethods = ConcurrentHashMap.newKeySet();
    private static final Map<AnalysisType, ClassNode> bytecodeCache = new ConcurrentHashMap<>();

    @Override
    public void duringAnalysis(DuringAnalysisAccess access) {
        if (!Options.StrictConstantReflection.getValue()) {
            return;
        }

        AnalysisUniverse universe = ((FeatureImpl.DuringAnalysisAccessImpl) access).getUniverse();
        ImageClassLoader loader = ((FeatureImpl.DuringAnalysisAccessImpl) access).getImageClassLoader();

        List<AnalysisMethod> methodsToAnalyze = universe.getMethods().stream()
                .filter(m -> !analyzedMethods.contains(m))
                .filter(AnalysisMethod::isReachable)
                .filter(m -> !m.isInBaseLayer())
                .filter(m -> m.getDeclaringClass().getJavaClass().getClassLoader() == loader.getClassLoader())
                .toList();
        analyzedMethods.addAll(methodsToAnalyze);

        boolean newReflectionRegistration = false;
        for (AnalysisMethod method : methodsToAnalyze) {
            if (!method.hasBytecodes()) {
                continue;
            }
            newReflectionRegistration |= analyzeMethod(method);
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

    private boolean analyzeMethod(AnalysisMethod method) {
        ClassNode classNode = getClassBytecode(method.getDeclaringClass());
        if (classNode == null) {
            return false;
        }

        MethodNode methodNode = findMethodNode(method, classNode.methods);
        if (methodNode == null) {
            return false;
        }

        Analyzer<SourceValue> analyzer = new ControlFlowGraphAnalyzer<>(new SourceInterpreter());
        try {
            analyzer.analyze(classNode.name, methodNode);
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
                new ConstantBooleanAnalyzer(instructions, frames),
                new ConstantClassAnalyzer(instructions, frames),
                new ConstantArrayAnalyzer<>(instructions, frames, new ConstantClassAnalyzer(instructions, frames)),
                new ConstantMethodTypeAnalyzer(instructions, frames),
                new ConstantMethodHandlesLookupAnalyzer(instructions, frames)
        );
        Map<MethodInsnNode, Object> constantCalls = new HashMap<>();

        /*
         * ASM internally reorders constant pool entries, which can result in certain instructions
         * getting replaced with their wide versions or vice-versa (i.e. LDC and LDC_W). This causes
         * a mismatch in BCIs in the original bytecode and ASM analyzed bytecode, so we're using the
         * bytecode stream from the JVM CI object for logging purposes.
         */
        StrictConstantReflectionFeatureLogger logger = new StrictConstantReflectionFeatureLogger(method);

        boolean hasConstantReflection = false;
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                BiFunction<AnalyzerSuite, CallContext, Object> handler = ConstantCallHandlers.get(Utils.encodeMethodCall(methodCall));
                if (handler != null) {
                    Object result = handler.apply(analyzerSuite, new CallContext(method, frames[i], methodCall, constantCalls));
                    if (result != null) {
                        hasConstantReflection = true;
                        logger.createLogEntry(result);
                    }
                }
                logger.moveToNextInvocation();
            }
        }

        return hasConstantReflection;
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        String logLocation = Options.StrictConstantReflectionLog.getValue();
        if (logLocation != null) {
            StrictConstantReflectionFeatureLogger.dumpLog(logLocation);
        }
    }
}

record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantBooleanAnalyzer booleanAnalyzer,
                     ConstantClassAnalyzer classAnalyzer, ConstantArrayAnalyzer<Class<?>> classArrayAnalyzer,
                     ConstantMethodTypeAnalyzer methodTypeAnalyzer, ConstantMethodHandlesLookupAnalyzer methodHandlesLookupAnalyzer) {

}

record CallContext(AnalysisMethod caller, Frame<SourceValue> frame, MethodInsnNode callSite, Map<MethodInsnNode, Object> constantCalls) {

}
