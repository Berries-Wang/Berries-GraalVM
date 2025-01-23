package com.oracle.svm.reflectionagent;

import com.oracle.svm.core.jni.headers.JNIEnvironment;
import com.oracle.svm.core.jni.headers.JNIObjectHandle;
import com.oracle.svm.jvmtiagentbase.JNIHandleSet;

import static com.oracle.svm.core.jni.JNIObjectHandles.nullHandle;

public class NativeImageReflectionAgentJNIHandleSet extends JNIHandleSet {

    final JNIObjectHandle classLoader;
    final JNIObjectHandle jdkInternalReflectDelegatingClassLoader;

    public NativeImageReflectionAgentJNIHandleSet(JNIEnvironment env) {
        super(env);
        classLoader = newClassGlobalRef(env, "java/lang/ClassLoader");
        JNIObjectHandle reflectLoader = findClassOptional(env, "jdk/internal/reflect/DelegatingClassLoader");
        jdkInternalReflectDelegatingClassLoader = reflectLoader.equal(nullHandle()) ? nullHandle() : newTrackedGlobalRef(env, reflectLoader);
    }
}
