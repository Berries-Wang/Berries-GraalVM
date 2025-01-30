package com.oracle.svm.reflectionagent;

import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;

public final class MethodCallUtils {

    private MethodCallUtils() {

    }

    public record Signature(String owner, String name, String desc) {

        public Signature(MethodInsnNode methodCall) {
            this(methodCall.owner, methodCall.name, methodCall.desc);
        }
    }

    public static SourceValue getCallArg(MethodInsnNode call, int argIdx, Frame<SourceValue> frame) {
        int numOfArgs = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == INVOKESTATIC ? 0 : 1);
        int stackPos = frame.getStackSize() - numOfArgs + argIdx;
        return frame.getStack(stackPos);
    }
}
