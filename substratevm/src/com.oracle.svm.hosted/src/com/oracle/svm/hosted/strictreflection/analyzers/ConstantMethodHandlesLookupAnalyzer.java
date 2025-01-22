package com.oracle.svm.hosted.strictreflection.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.invoke.MethodHandles;
import java.util.Optional;

public class ConstantMethodHandlesLookupAnalyzer extends ConstantValueAnalyzer<MethodHandles.Lookup> {

    public ConstantMethodHandlesLookupAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected Optional<MethodHandles.Lookup> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return Optional.empty();
    }
}
