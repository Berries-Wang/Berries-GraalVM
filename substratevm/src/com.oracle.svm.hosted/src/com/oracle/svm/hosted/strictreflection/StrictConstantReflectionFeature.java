package com.oracle.svm.hosted.strictreflection;

import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.option.HostedOptionKey;
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
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

@AutomaticallyRegisteredFeature
public class StrictConstantReflectionFeature implements InternalFeature {

    public static class Options {
        @Option(help = "Enable an optimization independent constant reflection analysis.")
        public static final HostedOptionKey<Boolean> StrictConstantReflection = new HostedOptionKey<>(false);
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (!Options.StrictConstantReflection.getValue()) {
            return;
        }

        access.registerSubtypeReachabilityHandler((acc, clazz) -> {
            String pathToClassFile = ClassUtil.getUnqualifiedName(clazz) + ".class";
            try (InputStream cfStream = clazz.getResourceAsStream(pathToClassFile)) {
                if (cfStream != null) {
                    analyzeBytecode(cfStream.readAllBytes());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, Object.class);
    }

    private void analyzeBytecode(byte[] bytecode) {
        ClassNode classNode = new ClassNode();

        ClassReader reader = new ClassReader(bytecode);
        reader.accept(classNode, 0);

        for (MethodNode methodNode : classNode.methods) {
            analyzeMethod(methodNode, classNode);
        }
    }

    private void analyzeMethod(MethodNode methodNode, ClassNode contextClassNode) {
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

        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                BiConsumer<AnalyzerSuite, CallContext> handler = reflectiveCallHandlers.get(Utils.encodeMethodCall(methodCall));
                if (handler == null) {
                    continue;
                }
                handler.accept(analyzerSuite, new CallContext(frames[i], methodCall, constantCalls));
            }
        }
    }

    private record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantClassAnalyzer classAnalyzer, ConstantArrayAnalyzer<Class<?>> classArrayAnalyzer) {

    }

    private record CallContext(Frame<SourceValue> frame, MethodInsnNode callSite, Map<MethodInsnNode, Object> constantCalls) {

    }

    private static SourceValue getCallArg(CallContext callContext, int argumentIndex) {
        return Utils.getCallArg(callContext.callSite, argumentIndex, callContext.frame);
    }

    private static final Map<String, BiConsumer<AnalyzerSuite, CallContext>> reflectiveCallHandlers = new HashMap<>() {
        {
            put(Utils.encodeMethodCall("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), StrictConstantReflectionFeature::classAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), StrictConstantReflectionFeature::fieldAccessHandler);
            put(Utils.encodeMethodCall("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"), StrictConstantReflectionFeature::declaredFieldAccessHandler);
        }
    };

    private static void classAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        Optional<String> className = analyzerSuite.stringAnalyzer.inferConstant(getCallArg(callContext, 0), callContext.constantCalls);
        if (className.isPresent()) {
            RuntimeReflection.registerClassLookup(className.get());
            try {
                callContext.constantCalls.put(callContext.callSite, Class.forName(className.get(), false, ClassLoader.getSystemClassLoader()));
            } catch (ClassNotFoundException e) {
                // The call will throw an exception during runtime - ignore for the rest of the analysis.
            }
        }
    }

    private static void fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext, boolean declaredOnly) {
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
        }
    }

    private static void fieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        fieldAccessHandler(analyzerSuite, callContext, false);
    }

    private static void declaredFieldAccessHandler(AnalyzerSuite analyzerSuite, CallContext callContext) {
        fieldAccessHandler(analyzerSuite, callContext, true);
    }
}
