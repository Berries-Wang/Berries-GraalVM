package com.oracle.svm.reflectionagent.analyzers;

import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Set;

import static jdk.internal.org.objectweb.asm.Opcodes.GETSTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.LDC;

public class ConstantClassAnalyzer extends ConstantValueAnalyzer {

    public ConstantClassAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames, Set<MethodInsnNode> constantCalls) {
        super(instructions, frames, constantCalls);
    }

    @Override
    protected boolean isConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return switch (sourceInstruction.getOpcode()) {
            case LDC -> {
                LdcInsnNode ldc = (LdcInsnNode) sourceInstruction;
                yield  ldc.cst instanceof Type type && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
            }
            case GETSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) sourceInstruction;
                yield isPrimitiveType(field);
            }
            default -> false;
        };
    }

    private boolean isPrimitiveType(FieldInsnNode field) {
        if (!field.name.equals("TYPE")) {
            return false;
        }

        return switch (field.owner) {
            case "java/lang/Byte", "java/lang/Char", "java/lang/Short", "java/lang/Integer", "java/lang/Long",
                 "java/lang/Float", "java/lang/Double", "java/lang/Void" -> true;
            default -> false;
        };
    }
}
