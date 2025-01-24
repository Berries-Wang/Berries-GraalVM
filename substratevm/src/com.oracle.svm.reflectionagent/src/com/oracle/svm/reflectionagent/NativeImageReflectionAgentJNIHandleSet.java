package com.oracle.svm.reflectionagent;

import com.oracle.svm.core.jni.headers.JNIEnvironment;
import com.oracle.svm.core.jni.headers.JNIMethodId;
import com.oracle.svm.core.jni.headers.JNIObjectHandle;
import com.oracle.svm.jvmtiagentbase.JNIHandleSet;
import com.oracle.svm.jvmtiagentbase.Support;

import static com.oracle.svm.core.jni.JNIObjectHandles.nullHandle;

public class NativeImageReflectionAgentJNIHandleSet extends JNIHandleSet {

    final JNIObjectHandle classLoader;
    final JNIObjectHandle jdkInternalReflectDelegatingClassLoader;

    final JNIObjectHandle systemClassLoader;
    final JNIObjectHandle platformClassLoader;
    final JNIObjectHandle builtinAppClassLoader;

    public NativeImageReflectionAgentJNIHandleSet(JNIEnvironment env) {
        super(env);
        classLoader = newClassGlobalRef(env, "java/lang/ClassLoader");

        JNIObjectHandle reflectLoader = findClassOptional(env, "jdk/internal/reflect/DelegatingClassLoader");
        jdkInternalReflectDelegatingClassLoader = reflectLoader.equal(nullHandle())
                ? nullHandle()
                : newTrackedGlobalRef(env, reflectLoader);

        JNIMethodId getSystemClassLoader = getMethodId(env, classLoader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;", true);
        systemClassLoader = newTrackedGlobalRef(env, Support.callObjectMethod(env, classLoader, getSystemClassLoader));

        JNIMethodId getPlatformClassLoader = getMethodIdOptional(env, classLoader, "getPlatformClassLoader", "()Ljava/lang/ClassLoader;", true);
        platformClassLoader = getPlatformClassLoader.equal(nullHandle())
                ? nullHandle()
                : newTrackedGlobalRef(env, Support.callObjectMethod(env, classLoader, getPlatformClassLoader));

        JNIMethodId getBuiltinAppClassLoader = getMethodIdOptional(env, classLoader, "getBuiltinAppClassLoader", "()Ljava/lang/ClassLoader;", true);
        builtinAppClassLoader = getBuiltinAppClassLoader.equal(nullHandle())
                ? nullHandle()
                : newTrackedGlobalRef(env, Support.callObjectMethod(env, classLoader, getBuiltinAppClassLoader));
    }
}
