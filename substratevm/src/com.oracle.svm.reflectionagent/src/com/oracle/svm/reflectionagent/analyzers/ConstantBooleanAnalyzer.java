package com.oracle.svm.reflectionagent.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Set;

import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_0;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_1;

public class ConstantBooleanAnalyzer extends ConstantValueAnalyzer {

    public ConstantBooleanAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames, Set<MethodInsnNode> constantCalls) {
        super(instructions, frames, constantCalls);
    }

    @Override
    protected boolean isConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return sourceInstruction.getOpcode() == ICONST_0 || sourceInstruction.getOpcode() == ICONST_1;
    }
}
