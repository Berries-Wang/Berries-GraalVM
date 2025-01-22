package com.oracle.svm.hosted.strictreflection.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.invoke.MethodType;
import java.util.Optional;

public class ConstantMethodTypeAnalyzer extends ConstantValueAnalyzer<MethodType> {

    public ConstantMethodTypeAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected Optional<MethodType> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return Optional.empty();
    }
}
