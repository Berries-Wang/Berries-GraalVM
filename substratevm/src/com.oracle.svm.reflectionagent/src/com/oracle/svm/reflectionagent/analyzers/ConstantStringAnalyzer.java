package com.oracle.svm.reflectionagent.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

public class ConstantStringAnalyzer extends ConstantValueAnalyzer {

    public ConstantStringAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected boolean isConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return sourceInstruction instanceof LdcInsnNode ldc && ldc.cst instanceof String;
    }
}