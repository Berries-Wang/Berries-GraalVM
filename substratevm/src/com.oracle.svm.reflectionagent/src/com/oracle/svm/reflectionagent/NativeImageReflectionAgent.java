package com.oracle.svm.reflectionagent;

import com.oracle.svm.core.c.function.CEntryPointOptions;
import com.oracle.svm.core.jni.headers.JNIEnvironment;
import com.oracle.svm.core.jni.headers.JNIJavaVM;
import com.oracle.svm.core.jni.headers.JNIMethodId;
import com.oracle.svm.core.jni.headers.JNIObjectHandle;
import com.oracle.svm.jvmtiagentbase.AgentIsolate;
import com.oracle.svm.jvmtiagentbase.JNIHandleSet;
import com.oracle.svm.jvmtiagentbase.JvmtiAgentBase;
import com.oracle.svm.jvmtiagentbase.Support;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEnv;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventCallbacks;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEventMode;
import com.oracle.svm.jvmtiagentbase.jvmti.JvmtiInterface;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.function.CEntryPointLiteral;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CIntPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.hosted.Feature;

import static com.oracle.svm.core.jni.JNIObjectHandles.nullHandle;
import static com.oracle.svm.jvmtiagentbase.Support.check;
import static com.oracle.svm.jvmtiagentbase.Support.jniFunctions;
import static com.oracle.svm.jvmtiagentbase.jvmti.JvmtiEvent.JVMTI_EVENT_CLASS_FILE_LOAD_HOOK;

public class NativeImageReflectionAgent extends JvmtiAgentBase<NativeImageReflectionAgentJNIHandleSet> {

    private static final CEntryPointLiteral<CFunctionPointer> ON_CLASS_FILE_LOAD_HOOK = CEntryPointLiteral.create(NativeImageReflectionAgent.class, "onClassFileLoadHook",
            JvmtiEnv.class, JNIEnvironment.class, JNIObjectHandle.class, JNIObjectHandle.class, CCharPointer.class, JNIObjectHandle.class, int.class, CCharPointer.class, CIntPointer.class,
            CCharPointerPointer.class);

    private static JNIObjectHandle[] builtinClassLoaders;

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
        setupClassFileLoadEvent(jvmti, jni);
    }

    private static void setupClassFileLoadEvent(JvmtiEnv jvmti, JNIEnvironment jni) {
        NativeImageReflectionAgent agent = singleton();
        JNIObjectHandle classLoader = agent.handles().classLoader;
        String[] classLoaderGetters = new String[] {"getSystemClassLoader", "getPlatformClassLoader", "getBuiltinAppClassLoader"};
        builtinClassLoaders = new JNIObjectHandle[classLoaderGetters.length];
        for (int i = 0; i < classLoaderGetters.length; i++) {
            JNIMethodId getterHandle = agent.handles().getMethodId(jni, classLoader, classLoaderGetters[i], "()Ljava/lang/ClassLoader;", true);
            builtinClassLoaders[i] = agent.handles().newTrackedGlobalRef(jni, Support.callObjectMethod(jni, classLoader, getterHandle));
        }
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

        String javaName = CTypeConversion.toJavaString(name);
        System.out.println("Loaded class " + javaName);

        byte[] clazzData = new byte[classDataLen];
        CTypeConversion.asByteBuffer(classData, classDataLen).get(clazzData);

        byte[] newClazzData = instrumentClass(clazzData);
        int newClazzDataLen = newClazzData.length;

        Support.check(jvmti.getFunctions().Allocate().invoke(jvmti, newClazzDataLen, newClassData));
        CTypeConversion.asByteBuffer(newClassData.read(), newClazzDataLen).put(newClazzData);
        newClassDataLen.write(newClazzDataLen);
    }

    private static boolean shouldIgnoreClassLoader(JNIEnvironment jni, JNIObjectHandle loader) {
        if (loader.equal(nullHandle())) {
            return true;
        }
        for (JNIObjectHandle builtinLoader : builtinClassLoaders) {
            if (jniFunctions().getIsSameObject().invoke(jni, loader, builtinLoader)) {
                return true;
            }
        }
        NativeImageReflectionAgent agent = singleton();
        JNIObjectHandle jdkInternalReflectDelegatingClassLoader = agent.handles().jdkInternalReflectDelegatingClassLoader;
        if (!jdkInternalReflectDelegatingClassLoader.equal(nullHandle()) && jniFunctions().getIsInstanceOf().invoke(jni, loader, jdkInternalReflectDelegatingClassLoader)) {
            return true;
        }
        return false;
    }

    private static byte[] instrumentClass(byte[] classData) {
        ClassReader reader = new ClassReader(classData);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        ClassWriter writer = new ClassWriter(0);
        classNode.accept(writer);
        return writer.toByteArray();
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
