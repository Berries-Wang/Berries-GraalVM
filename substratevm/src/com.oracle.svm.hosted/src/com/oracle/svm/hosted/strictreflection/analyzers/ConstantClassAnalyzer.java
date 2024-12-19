package com.oracle.svm.hosted.strictreflection.analyzers;

import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Optional;

import static jdk.internal.org.objectweb.asm.Opcodes.GETSTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.LDC;

public class ConstantClassAnalyzer extends ConstantValueAnalyzer<Class<?>> {

    public ConstantClassAnalyzer(AbstractInsnNode[] instructions, Frame<SourceValue>[] frames) {
        super(instructions, frames);
    }

    @Override
    protected Optional<Class<?>> inferConstant(SourceValue value, AbstractInsnNode sourceInstruction, Frame<SourceValue> sourceInstructionFrame) {
        return switch (sourceInstruction.getOpcode()) {
            case LDC -> {
                LdcInsnNode ldc = (LdcInsnNode) sourceInstruction;
                if (ldc.cst instanceof Type type && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
                    String className = type.getInternalName().replace('/', '.');
                    yield inferClassConstant(className);
                } else {
                    yield Optional.empty();
                }
            }
            case GETSTATIC -> {
                FieldInsnNode field = (FieldInsnNode) sourceInstruction;
                yield inferPrimitiveTypeClass(field);
            }
            default -> Optional.empty();
        };
    }

    private Optional<Class<?>> inferClassConstant(String className) {
        try {
            return Optional.of(Class.forName(className, false, ClassLoader.getSystemClassLoader()));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    private Optional<Class<?>> inferPrimitiveTypeClass(FieldInsnNode field) {
        if (!field.name.equals("TYPE")) {
            return Optional.empty();
        }

        Class<?> clazz = switch (field.owner) {
            case "java/lang/Byte" -> byte.class;
            case "java/lang/Char" -> char.class;
            case "java/lang/Short" -> short.class;
            case "java/lang/Integer" -> int.class;
            case "java/lang/Long" -> long.class;
            case "java/lang/Float" -> float.class;
            case "java/lang/Double" -> double.class;
            default -> null;
        };

        return clazz != null ? Optional.of(clazz) : Optional.empty();
    }
}
