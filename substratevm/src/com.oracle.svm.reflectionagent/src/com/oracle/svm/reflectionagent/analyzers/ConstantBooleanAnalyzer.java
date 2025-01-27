package com.oracle.svm.reflectionagent.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_0;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_1;

public class ConstantBooleanAnalyzer extends ConstantValueAnalyzer {

    public ConstantBooleanAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected boolean isConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return sourceInstruction.getOpcode() == ICONST_0 || sourceInstruction.getOpcode() == ICONST_1;
    }
}
