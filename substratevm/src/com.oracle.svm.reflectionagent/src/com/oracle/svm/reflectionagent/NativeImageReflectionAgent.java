package com.oracle.svm.reflectionagent;

import com.oracle.svm.core.annotate.ConstantTags;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import com.oracle.svm.core.jni.headers.JNIEnvironment;
import com.oracle.svm.core.jni.headers.JNIJavaVM;
import com.oracle.svm.core.jni.headers.JNIObjectHandle;
import com.oracle.svm.jvmtiagentbase.AgentIsolate;
import com.oracle.svm.jvmtiagentbase.JNIHandleSet;
import com.oracle.svm.jvmtiagentbase.JvmtiAgentBase;
import com.oracle.svm.jvmtiagentbase.Support;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEnv;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventCallbacks;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventMode;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiInterface;
import com.oracle.svm.reflectionagent.analyzers.ConstantBooleanAnalyzer;
import com.oracle.svm.reflectionagent.analyzers.ConstantStringAnalyzer;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceInterpreter;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.hosted.Feature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import static com.oracle.svm.core.jni.JNIObjectHandles.nullHandle;
import static com.oracle.svm.jvmtiagentbase.Support.check;
import static com.oracle.svm.jvmtiagentbase.Support.jniFunctions;
import static com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEvent.JVMTI_EVENT_CLASS_FILE_LOAD_HOOK;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;

@SuppressWarnings("unused")
public class NativeImageReflectionAgent extends JvmtiAgentBase<NativeImageReflectionAgentJNIHandleSet> {

    private static final Class<?> CONSTANT_TAGS_CLASS = ConstantTags.class;

    private static final Map<Signature, BiPredicate<AnalyzerSuite, CallContext>> REFLECTIVE_CALL_HANDLERS = createReflectiveCallHandlers();

    private static Map<Signature, BiPredicate<AnalyzerSuite, CallContext>> createReflectiveCallHandlers() {
        Map<Signature, BiPredicate<AnalyzerSuite, CallContext>> callHandlers = new HashMap<>();
        callHandlers.put(new Signature("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"), NativeImageReflectionAgent::isForName1Constant);
        callHandlers.put(new Signature("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"), NativeImageReflectionAgent::isForName3Constant);
        return callHandlers;
    }

    private static final CEntryPointLiteral<CFunctionPointer> ON_CLASS_FILE_LOAD_HOOK = CEntryPointLiteral.create(NativeImageReflectionAgent.class, "onClassFileLoadHook",
            JvmtiEnv.class, JNIEnvironment.class, JNIObjectHandle.class, JNIObjectHandle.class, CCharPointer.class, JNIObjectHandle.class, int.class, CCharPointer.class, CIntPointer.class,
            CCharPointerPointer.class);

    @Override
    protected JNIHandleSet constructJavaHandles(JNIEnvironment env) {
        return new NativeImageReflectionAgentJNIHandleSet(env);
    }

    @Override
    protected int onLoadCallback(JNIJavaVM vm, JvmtiEnv jvmti, JvmtiEventCallbacks callbacks, String options) {
        callbacks.setClassFileLoadHook(ON_CLASS_FILE_LOAD_HOOK.getFunctionPointer());
        return 0;
    }

    @Override
    protected void onVMInitCallback(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle thread) {
        check(jvmti.getFunctions().SetEventNotificationMode().invoke(jvmti, JvmtiEventMode.JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullHandle()));
    }

    @CEntryPoint
    @CEntryPointOptions(prologue = AgentIsolate.Prologue.class)
    @SuppressWarnings("unused")
    private static void onClassFileLoadHook(JvmtiEnv jvmti, JNIEnvironment jni, JNIObjectHandle classBeingRedefined,
                                            JNIObjectHandle loader, CCharPointer name, JNIObjectHandle protectionDomain, int classDataLen, CCharPointer classData,
                                            CIntPointer newClassDataLen, CCharPointerPointer newClassData) {
        if (shouldIgnoreClassLoader(jni, loader)) {
            return;
        }

        byte[] clazzData = new byte[classDataLen];
        CTypeConversion.asByteBuffer(classData, classDataLen).get(clazzData);
        try {
            byte[] newClazzData = instrumentClass(clazzData);
            int newClazzDataLen = newClazzData.length;
            Support.check(jvmti.getFunctions().Allocate().invoke(jvmti, newClazzDataLen, newClassData));
            CTypeConversion.asByteBuffer(newClassData.read(), newClazzDataLen).put(newClazzData);
            newClassDataLen.write(newClazzDataLen);
        } catch (AnalyzerException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * We're only interested in analyzing and instrumenting user provided classes,
     * so we're handling that by checking which class loader the class was loaded by.
     * In case a class was loaded by a builtin class loader, we ignore it.
     */
    private static boolean shouldIgnoreClassLoader(JNIEnvironment jni, JNIObjectHandle loader) {
        NativeImageReflectionAgent agent = singleton();
        JNIObjectHandle platformClassLoader = agent.handles().platformClassLoader;
        JNIObjectHandle builtinAppClassLoader = agent.handles().builtinAppClassLoader;
        JNIObjectHandle jdkInternalReflectDelegatingClassLoader = agent.handles().jdkInternalReflectDelegatingClassLoader;

        return loader.equal(nullHandle()) // Bootstrap class loader
                || jniFunctions().getIsSameObject().invoke(jni, loader, agent.handles().systemClassLoader)
                || !platformClassLoader.equal(nullHandle()) && jniFunctions().getIsSameObject().invoke(jni, loader, platformClassLoader)
                || !builtinAppClassLoader.equal(nullHandle()) && jniFunctions().getIsSameObject().invoke(jni, loader, builtinAppClassLoader)
                || !jdkInternalReflectDelegatingClassLoader.equal(nullHandle()) && jniFunctions().getIsInstanceOf().invoke(jni, loader, jdkInternalReflectDelegatingClassLoader);
    }

    private static byte[] instrumentClass(byte[] classData) throws AnalyzerException {
        ClassReader reader = new ClassReader(classData);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        for (MethodNode methodNode : classNode.methods) {
            instrumentMethod(methodNode, classNode);
        }
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static void instrumentMethod(MethodNode methodNode, ClassNode classNode) throws AnalyzerException {
        Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
        Frame<SourceValue>[] frames = analyzer.analyze(classNode.name, methodNode);
        AbstractInsnNode[] instructions = methodNode.instructions.toArray();

        AnalyzerSuite analyzerSuite = new AnalyzerSuite(
                new ConstantStringAnalyzer(instructions, frames),
                new ConstantBooleanAnalyzer(instructions, frames)
        );

        List<MethodInsnNode> constantCalls = new ArrayList<>();
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                BiPredicate<AnalyzerSuite, CallContext> handler = REFLECTIVE_CALL_HANDLERS.get(new Signature(methodCall));
                if (handler != null && handler.test(analyzerSuite, new CallContext(methodCall, frames[i]))) {
                    constantCalls.add(methodCall);
                }
            }
        }

        constantCalls.forEach(NativeImageReflectionAgent::tagAsConstant);
    }

    private static void tagAsConstant(MethodInsnNode methodCall) {
        methodCall.owner = CONSTANT_TAGS_CLASS.getName().replace('.', '/');
    }

    private static boolean isForName1Constant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.stringAnalyzer.isConstant(getCallArg(callContext.call, 0, callContext.frame));
    }

    private static boolean isForName3Constant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.stringAnalyzer.isConstant(getCallArg(callContext.call, 0, callContext.frame))
                && analyzers.booleanAnalyzer.isConstant(getCallArg(callContext.call, 1, callContext.frame));
    }

    record CallContext(MethodInsnNode call, Frame<SourceValue> frame) {

    }

    record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantBooleanAnalyzer booleanAnalyzer) {

    }

    record Signature(String owner, String name, String desc) {

        Signature(MethodInsnNode methodCall) {
            this(methodCall.owner, methodCall.name, methodCall.desc);
        }
    }

    public static SourceValue getCallArg(MethodInsnNode call, int argIdx, Frame<SourceValue> frame) {
        int numOfArgs = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == INVOKESTATIC ? 0 : 1);
        int stackPos = frame.getStackSize() - numOfArgs + argIdx;
        return frame.getStack(stackPos);
    }

    @Override
    protected int onUnloadCallback(JNIJavaVM vm) {
        return 0;
    }

    @Override
    protected void onVMStartCallback(JvmtiEnv jvmti, JNIEnvironment jni) {

    }

    @Override
    protected void onVMDeathCallback(JvmtiEnv jvmti, JNIEnvironment jni) {

    }

    @Override
    protected int getRequiredJvmtiVersion() {
        return JvmtiInterface.JVMTI_VERSION_9;
    }

    @SuppressWarnings("unused")
    public static class RegistrationFeature implements Feature {

        @Override
        public void afterRegistration(AfterRegistrationAccess access) {
            JvmtiAgentBase.registerAgent(new NativeImageReflectionAgent());
        }
    }
}
