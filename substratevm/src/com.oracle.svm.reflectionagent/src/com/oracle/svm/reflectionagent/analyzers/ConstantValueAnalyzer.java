package com.oracle.svm.reflectionagent.analyzers;

import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Arrays;

import static jdk.internal.org.objectweb.asm.Opcodes.ALOAD;
import static jdk.internal.org.objectweb.asm.Opcodes.ASTORE;

public abstract class ConstantValueAnalyzer {
    private final AbstractInsnNode[] instructions;
    private final Frame<SourceValue>[] frames;

    public ConstantValueAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        this.instructions = instructions;
        this.frames = frames;
    }

    public boolean isConstant(SourceValue value) {
        if (value.insns.size() != 1) {
            return false;
        }

        AbstractInsnNode sourceInstruction = value.insns.iterator().next();
        int sourceInstructionIndex = Arrays.asList(instructions).indexOf(sourceInstruction);
        Frame<SourceValue> sourceInstructionFrame = frames[sourceInstructionIndex];

        if (sourceInstruction.getOpcode() == ALOAD) {
            SourceValue sourceValue = sourceInstructionFrame.getLocal(((VarInsnNode) sourceInstruction).var);
            return isConstant(sourceValue);
        } else if (sourceInstruction.getOpcode() == ASTORE) {
            SourceValue sourceValue = sourceInstructionFrame.getStack(sourceInstructionFrame.getStackSize() - 1);
            return isConstant(sourceValue);
        }

        return isConstant(value, sourceInstruction, sourceInstructionFrame);
    }

    protected abstract boolean isConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame);
}
