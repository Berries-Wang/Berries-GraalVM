/*
 * Copyright (c) 2024, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.reflectionagent;

import com.oracle.svm.configure.trace.AccessAdvisor;
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
import com.oracle.svm.reflectionagent.analyzers.ConstantArrayAnalyzer;
import com.oracle.svm.reflectionagent.analyzers.ConstantBooleanAnalyzer;
import com.oracle.svm.reflectionagent.analyzers.ConstantClassAnalyzer;
import com.oracle.svm.reflectionagent.analyzers.ConstantStringAnalyzer;
import com.oracle.svm.reflectionagent.cfg.ControlFlowGraphAnalyzer;
import com.oracle.svm.reflectionagent.cfg.ControlFlowGraphNode;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
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
import org.graalvm.nativeimage.impl.reflectiontags.ConstantTags;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static com.oracle.svm.core.jni.JNIObjectHandles.nullHandle;
import static com.oracle.svm.jvmtiagentbase.Support.check;
import static com.oracle.svm.jvmtiagentbase.Support.jniFunctions;
import static com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEvent.JVMTI_EVENT_CLASS_FILE_LOAD_HOOK;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;

/**
 * A JVMTI agent which analyzes user provided classes and tags reflective method calls which can be
 * proven constant.
 * <p>
 * This way of marking reflective calls as constant decouples the analysis and image runtime
 * behaviour w.r.t. reflection from various optimizations executed on IR graphs.
 */
@SuppressWarnings("unused")
public class NativeImageReflectionAgent extends JvmtiAgentBase<NativeImageReflectionAgentJNIHandleSet> {

    private static final Class<?> CONSTANT_TAGS_CLASS = ConstantTags.class;

    private static final Map<MethodCallUtils.Signature, BiPredicate<AnalyzerSuite, CallContext>> REFLECTIVE_CALL_HANDLERS = createReflectiveCallHandlers();
    private static final Map<MethodCallUtils.Signature, BiPredicate<AnalyzerSuite, CallContext>> NON_REFLECTIVE_CALL_HANDLERS = createNonReflectiveCallHandlers();

    /**
     * Defines the reflective methods (which can potentially throw a
     * {@link org.graalvm.nativeimage.MissingReflectionRegistrationError}) which we want to tag for
     * folding in {@link com.oracle.svm.hosted.snippets.ReflectionPlugins}.
     * <p>
     * If proven as constant by our analysis, calls to these methods will be tagged by redirecting
     * their owner to {@link org.graalvm.nativeimage.impl.reflectiontags.ConstantTags} (making them static
     * invocations in the process if necessary).
     */
    private static Map<MethodCallUtils.Signature, BiPredicate<AnalyzerSuite, CallContext>> createReflectiveCallHandlers() {
        Map<MethodCallUtils.Signature, BiPredicate<AnalyzerSuite, CallContext>> callHandlers = new HashMap<>();
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;"),
                        NativeImageReflectionAgent::isForName1Constant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"),
                        NativeImageReflectionAgent::isForName3Constant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                        NativeImageReflectionAgent::isFieldQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;"),
                        NativeImageReflectionAgent::isFieldQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"),
                        NativeImageReflectionAgent::isConstructorQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredConstructor", "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;"),
                        NativeImageReflectionAgent::isConstructorQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
                        NativeImageReflectionAgent::isMethodQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;"),
                        NativeImageReflectionAgent::isMethodQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getFields", "()[Ljava/lang/reflect/Field;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredFields", "()[Ljava/lang/reflect/Field;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getConstructors", "()[Ljava/lang/reflect/Constructor;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredConstructors", "()[Ljava/lang/reflect/Constructor;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getMethods", "()[Ljava/lang/reflect/Method;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredMethods", "()[Ljava/lang/reflect/Method;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getClasses", "()[Ljava/lang/Class;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getDeclaredClasses", "()[Ljava/lang/Class;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getNestMembers", "()[Ljava/lang/Class;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getPermittedSubclasses", "()[Ljava/lang/Class;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getRecordComponents", "()[Ljava/lang/reflect/RecordComponent;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        callHandlers.put(new MethodCallUtils.Signature("java/lang/Class", "getSigners", "()[Ljava/lang/Object;"),
                        NativeImageReflectionAgent::isBulkQueryConstant);
        return callHandlers;
    }

    /**
     * Defines methods which we still need to track, but not tag with
     * {@link org.graalvm.nativeimage.impl.reflectiontags.ConstantTags}. An example of this are various methods for
     * {@link java.lang.invoke.MethodType} construction.
     */
    private static Map<MethodCallUtils.Signature, BiPredicate<AnalyzerSuite, CallContext>> createNonReflectiveCallHandlers() {
        Map<MethodCallUtils.Signature, BiPredicate<AnalyzerSuite, CallContext>> callHandlers = new HashMap<>();
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

        String className = CTypeConversion.toJavaString(name);
        if (AccessAdvisor.PROXY_CLASS_NAME_PATTERN.matcher(className).matches()) {
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
     * We're only interested in analyzing and instrumenting user provided classes, so we're handling
     * that by checking which class loader the class was loaded by. In case a class was loaded by a
     * builtin class loader, we ignore it.
     */
    private static boolean shouldIgnoreClassLoader(JNIEnvironment jni, JNIObjectHandle loader) {
        NativeImageReflectionAgent agent = singleton();
        JNIObjectHandle platformClassLoader = agent.handles().platformClassLoader;
        JNIObjectHandle builtinAppClassLoader = agent.handles().builtinAppClassLoader;
        JNIObjectHandle jdkInternalReflectDelegatingClassLoader = agent.handles().jdkInternalReflectDelegatingClassLoader;

        return loader.equal(nullHandle()) // Bootstrap class loader
                        || jniFunctions().getIsSameObject().invoke(jni, loader, agent.handles().systemClassLoader) ||
                        !platformClassLoader.equal(nullHandle()) && jniFunctions().getIsSameObject().invoke(jni, loader, platformClassLoader) ||
                        !builtinAppClassLoader.equal(nullHandle()) && jniFunctions().getIsSameObject().invoke(jni, loader, builtinAppClassLoader) ||
                        !jdkInternalReflectDelegatingClassLoader.equal(nullHandle()) && jniFunctions().getIsInstanceOf().invoke(jni, loader, jdkInternalReflectDelegatingClassLoader);
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
        Analyzer<SourceValue> analyzer = new ControlFlowGraphAnalyzer<>(new SourceInterpreter());

        AbstractInsnNode[] instructions = methodNode.instructions.toArray();
        @SuppressWarnings("unchecked")
        ControlFlowGraphNode<SourceValue>[] frames = Arrays.stream(analyzer.analyze(classNode.name, methodNode))
                        .map(frame -> (ControlFlowGraphNode<SourceValue>) frame).toArray(ControlFlowGraphNode[]::new);
        Set<MethodInsnNode> constantCalls = new HashSet<>();

        Set<MethodCallUtils.Signature> allCalls = new HashSet<>(REFLECTIVE_CALL_HANDLERS.keySet());
        allCalls.addAll(NON_REFLECTIVE_CALL_HANDLERS.keySet());

        AnalyzerSuite analyzerSuite = new AnalyzerSuite(
                        new ConstantStringAnalyzer(instructions, frames, constantCalls),
                        new ConstantBooleanAnalyzer(instructions, frames, constantCalls),
                        new ConstantClassAnalyzer(instructions, frames, constantCalls),
                        new ConstantArrayAnalyzer(instructions, frames, allCalls, new ConstantClassAnalyzer(instructions, frames, constantCalls)));

        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i] instanceof MethodInsnNode methodCall) {
                BiPredicate<AnalyzerSuite, CallContext> handler = REFLECTIVE_CALL_HANDLERS.get(new MethodCallUtils.Signature(methodCall));
                if (handler == null) {
                    handler = NON_REFLECTIVE_CALL_HANDLERS.get(new MethodCallUtils.Signature(methodCall));
                }
                if (handler != null && handler.test(analyzerSuite, new CallContext(methodCall, frames[i]))) {
                    constantCalls.add(methodCall);
                }
            }
        }

        constantCalls.stream()
                        .filter(cc -> REFLECTIVE_CALL_HANDLERS.containsKey(new MethodCallUtils.Signature(cc)))
                        .forEach(NativeImageReflectionAgent::tagAsConstant);
    }

    private static void tagAsConstant(MethodInsnNode methodCall) {
        if (methodCall.getOpcode() != INVOKESTATIC) {
            methodCall.setOpcode(INVOKESTATIC);
            methodCall.desc = "(L" + methodCall.owner + ";" + methodCall.desc.substring(1);
        }
        methodCall.owner = CONSTANT_TAGS_CLASS.getName().replace('.', '/');
    }

    private static boolean isForName1Constant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.stringAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 0, callContext.frame));
    }

    private static boolean isForName3Constant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.stringAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 0, callContext.frame)) &&
                        analyzers.booleanAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 1, callContext.frame));
    }

    private static boolean isFieldQueryConstant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.classAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 0, callContext.frame)) &&
                        analyzers.stringAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 1, callContext.frame));
    }

    private static boolean isConstructorQueryConstant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.classAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 0, callContext.frame)) &&
                        analyzers.classArrayAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 1, callContext.frame), callContext.call);
    }

    private static boolean isMethodQueryConstant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.classAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 0, callContext.frame)) &&
                        analyzers.stringAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 1, callContext.frame)) &&
                        analyzers.classArrayAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 2, callContext.frame), callContext.call);
    }

    private static boolean isBulkQueryConstant(AnalyzerSuite analyzers, CallContext callContext) {
        return analyzers.classAnalyzer.isConstant(MethodCallUtils.getCallArg(callContext.call, 0, callContext.frame));
    }

    record CallContext(MethodInsnNode call, Frame<SourceValue> frame) {

    }

    record AnalyzerSuite(ConstantStringAnalyzer stringAnalyzer, ConstantBooleanAnalyzer booleanAnalyzer,
                    ConstantClassAnalyzer classAnalyzer, ConstantArrayAnalyzer classArrayAnalyzer) {

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
