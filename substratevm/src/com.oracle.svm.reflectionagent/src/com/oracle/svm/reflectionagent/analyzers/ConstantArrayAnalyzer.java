package com.oracle.svm.reflectionagent.analyzers;

import com.oracle.svm.reflectionagent.MethodCallUtils;
import com.oracle.svm.reflectionagent.cfg.ControlFlowGraphNode;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.IntInsnNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.TypeInsnNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Frame;
import jdk.internal.org.objectweb.asm.tree.analysis.SourceValue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static jdk.internal.org.objectweb.asm.Opcodes.AASTORE;
import static jdk.internal.org.objectweb.asm.Opcodes.ALOAD;
import static jdk.internal.org.objectweb.asm.Opcodes.ANEWARRAY;
import static jdk.internal.org.objectweb.asm.Opcodes.ASTORE;
import static jdk.internal.org.objectweb.asm.Opcodes.BIPUSH;
import static jdk.internal.org.objectweb.asm.Opcodes.DUP;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_0;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_1;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_2;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_3;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_4;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_5;
import static jdk.internal.org.objectweb.asm.Opcodes.ICONST_M1;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKESTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static jdk.internal.org.objectweb.asm.Opcodes.LDC;
import static jdk.internal.org.objectweb.asm.Opcodes.PUTFIELD;
import static jdk.internal.org.objectweb.asm.Opcodes.PUTSTATIC;
import static jdk.internal.org.objectweb.asm.Opcodes.SIPUSH;

public class ConstantArrayAnalyzer {

    private final AbstractInsnNode[] instructions;
    private final ControlFlowGraphNode<SourceValue>[] frames;
    private final Set<MethodCallUtils.Signature> safeMethods;
    private final ConstantValueAnalyzer valueAnalyzer;

    public ConstantArrayAnalyzer(AbstractInsnNode[] instructions, ControlFlowGraphNode<SourceValue>[] frames,
                                 Set<MethodCallUtils.Signature> safeMethods, ConstantValueAnalyzer valueAnalyzer) {
        this.instructions = instructions;
        this.frames = frames;
        this.safeMethods = safeMethods;
        this.valueAnalyzer = valueAnalyzer;
    }

    public boolean isConstant(SourceValue value, AbstractInsnNode callSite) {
        return referenceIsConstant(value, callSite) && elementsAreConstant(value, callSite);
    }

    private boolean referenceIsConstant(SourceValue value, AbstractInsnNode callSite) {
        if (value.insns.size() != 1) {
            return false;
        }

        AbstractInsnNode sourceInstruction = value.insns.iterator().next();
        int sourceInstructionIndex = Arrays.asList(instructions).indexOf(sourceInstruction);
        Frame<SourceValue> sourceInstructionFrame = frames[sourceInstructionIndex];

        return switch (sourceInstruction.getOpcode()) {
            case ANEWARRAY -> {
                Optional<Integer> arraySize = extractConstInt(sourceInstructionFrame.getStack(sourceInstructionFrame.getStackSize() - 1));
                yield arraySize.isPresent();
            }
            case ALOAD -> {
                SourceValue sourceValue = sourceInstructionFrame.getLocal(((VarInsnNode) sourceInstruction).var);
                yield referenceIsConstant(sourceValue, callSite) && noForbiddenUsages(sourceValue.insns.iterator().next(), callSite);
            }
            case ASTORE -> {
                SourceValue sourceValue = sourceInstructionFrame.getStack(sourceInstructionFrame.getStackSize() - 1);
                yield referenceIsConstant(sourceValue, callSite) && sourceValue.insns.iterator().next().getOpcode() == ANEWARRAY;
            }
            default -> false;
        };
    }

    private static Optional<Integer> extractConstInt(SourceValue value) {
        if (value.insns.size() != 1) {
            return Optional.empty();
        }

        AbstractInsnNode sourceInstruction = value.insns.iterator().next();

        return switch (sourceInstruction.getOpcode()) {
            case ICONST_M1 -> Optional.of(-1);
            case ICONST_0 -> Optional.of(0);
            case ICONST_1 -> Optional.of(1);
            case ICONST_2 -> Optional.of(2);
            case ICONST_3 -> Optional.of(3);
            case ICONST_4 -> Optional.of(4);
            case ICONST_5 -> Optional.of(5);
            case BIPUSH, SIPUSH -> Optional.of(((IntInsnNode) sourceInstruction).operand);
            case LDC -> {
                LdcInsnNode ldc = (LdcInsnNode) sourceInstruction;
                if (ldc.cst instanceof Integer intValue) {
                    yield Optional.of(intValue);
                }
                yield Optional.empty();
            }
            default -> Optional.empty();
        };
    }

    private boolean noForbiddenUsages(AbstractInsnNode originalStoreInstruction, AbstractInsnNode callSite) {
        int callSiteInstructionIndex = Arrays.asList(instructions).indexOf(callSite);

        Stack<Integer> nodeIndices = new Stack<>();
        nodeIndices.add(callSiteInstructionIndex);

        boolean[] visited = new boolean[frames.length];
        while (!nodeIndices.isEmpty()) {
            Integer currentNodeIndex = nodeIndices.pop();
            visited[currentNodeIndex] = true;
            if (isForbiddenStore(currentNodeIndex, originalStoreInstruction) || isForbiddenMethodCall(currentNodeIndex, originalStoreInstruction)) {
                return false;
            }

            ControlFlowGraphNode<SourceValue> currentNode = frames[currentNodeIndex];
            for (int adjacent : currentNode.predecessors) {
                if (!visited[adjacent]) {
                    nodeIndices.push(adjacent);
                }
            }
        }

        return true;
    }

    private boolean isForbiddenStore(int instructionIndex, AbstractInsnNode originalStoreInstruction) {
        AbstractInsnNode instruction = instructions[instructionIndex];
        Frame<SourceValue> frame = frames[instructionIndex];

        if (Stream.of(Opcodes.ASTORE, PUTFIELD, PUTSTATIC).noneMatch(opc -> opc == instruction.getOpcode())) {
            return false;
        }

        SourceValue storeValue = frame.getStack(frame.getStackSize() - 1);
        return loadedValueTracesToStore(storeValue, originalStoreInstruction);
    }

    private boolean isForbiddenMethodCall(int instructionIndex, AbstractInsnNode originalStoreInstruction) {
        AbstractInsnNode instruction = instructions[instructionIndex];
        Frame<SourceValue> frame = frames[instructionIndex];

        if (Stream.of(INVOKEVIRTUAL, INVOKESPECIAL, INVOKESTATIC, INVOKEINTERFACE).noneMatch(opc -> opc == instruction.getOpcode())) {
            return false;
        }

        MethodInsnNode methodCall = (MethodInsnNode) instruction;
        // Check if method call is considered safe i.e. it is one of the reflective call methods we're analysing.
        if (safeMethods.contains(new MethodCallUtils.Signature(methodCall))) {
            return false;
        }

        int numOfArgs = Type.getArgumentTypes(methodCall.desc).length;
        return IntStream.range(0, numOfArgs)
                .anyMatch(i -> loadedValueTracesToStore(MethodCallUtils.getCallArg(methodCall, i, frame), originalStoreInstruction));
    }

    private boolean loadedValueTracesToStore(SourceValue value, AbstractInsnNode originalStoreInstruction) {
        return value.insns.stream().anyMatch(insn -> {
            if (insn.getOpcode() !=  ALOAD) {
                return false;
            }

            int loadInstructionIndex = Arrays.asList(instructions).indexOf(insn);
            Frame<SourceValue> loadInstructionFrame = frames[loadInstructionIndex];
            SourceValue loadSourceValue = loadInstructionFrame.getLocal(((VarInsnNode) insn).var);

            return loadSourceValue.insns.stream().anyMatch(storeInsn -> storeInsn == originalStoreInstruction);
        });
    }

    private Optional<TypeInsnNode> traceArrayRefToOrigin(SourceValue value) {
        if (value.insns.size() != 1) {
            return Optional.empty();
        }

        AbstractInsnNode sourceInstruction = value.insns.iterator().next();
        int sourceInstructionIndex = Arrays.asList(instructions).indexOf(sourceInstruction);
        Frame<SourceValue> sourceInstructionFrame = frames[sourceInstructionIndex];

        return switch (sourceInstruction.getOpcode()) {
            case ANEWARRAY -> Optional.of((TypeInsnNode) sourceInstruction);
            case ALOAD -> {
                SourceValue sourceValue = sourceInstructionFrame.getLocal(((VarInsnNode) sourceInstruction).var);
                yield traceArrayRefToOrigin(sourceValue);
            }
            case ASTORE -> {
                SourceValue sourceValue = sourceInstructionFrame.getStack(0);
                yield traceArrayRefToOrigin(sourceValue);
            }
            case DUP -> {
                SourceValue sourceValue = sourceInstructionFrame.getStack(sourceInstructionFrame.getStackSize() - 1);
                yield traceArrayRefToOrigin(sourceValue);
            }
            default -> Optional.empty();
        };
    }

    private boolean elementsAreConstant(SourceValue value, AbstractInsnNode callSite) {
        int callSiteInstructionIndex = Arrays.asList(instructions).indexOf(callSite);

        Optional<TypeInsnNode> arrayInitInsn = traceArrayRefToOrigin(value);
        if (arrayInitInsn.isEmpty()) {
            return false;
        }

        int arrayInitInstructionIndex = Arrays.asList(instructions).indexOf(arrayInitInsn.get());
        Frame<SourceValue> arrayInitInstructionFrame = frames[arrayInitInstructionIndex];

        SourceValue arraySizeValue = arrayInitInstructionFrame.getStack(arrayInitInstructionFrame.getStackSize() - 1);
        Optional<Integer> arraySize = extractConstInt(arraySizeValue);
        if (arraySize.isEmpty()) {
            return false;
        }

        Set<Integer> constantElements = new HashSet<>();

        for (int i = arrayInitInstructionIndex; i < callSiteInstructionIndex; i++) {
            AbstractInsnNode currentInstruction = instructions[i];
            ControlFlowGraphNode<SourceValue> currentInstructionFrame = frames[i];

            if (currentInstructionFrame.successors.size() != 1) {
                return false;
            }

            if (currentInstruction.getOpcode() == AASTORE) {
                SourceValue storedValue = currentInstructionFrame.getStack(currentInstructionFrame.getStackSize() - 1);
                SourceValue indexValue = currentInstructionFrame.getStack(currentInstructionFrame.getStackSize() - 2);
                SourceValue arrayRefValue = currentInstructionFrame.getStack(currentInstructionFrame.getStackSize() - 3);

                Optional<TypeInsnNode> arrayReference = traceArrayRefToOrigin(arrayRefValue);
                if (arrayReference.isEmpty() || arrayReference.get() != arrayInitInsn.get()) {
                    continue;
                }

                Optional<Integer> elementIndex = extractConstInt(indexValue);
                if (elementIndex.isEmpty() || !valueAnalyzer.isConstant(storedValue) || constantElements.contains(elementIndex.get())) {
                    return false;
                }

                constantElements.add(elementIndex.get());
            }
        }

        return constantElements.size() == arraySize.get();
    }
}
