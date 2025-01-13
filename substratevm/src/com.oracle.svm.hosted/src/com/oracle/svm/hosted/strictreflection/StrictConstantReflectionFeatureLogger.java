package com.oracle.svm.hosted.strictreflection;

import jdk.graal.compiler.bytecode.BytecodeStream;
import jdk.graal.compiler.bytecode.Bytecodes;
import jdk.graal.compiler.util.json.JsonBuilder;
import jdk.graal.compiler.util.json.JsonPrettyWriter;
import jdk.graal.compiler.util.json.JsonWriter;
import jdk.vm.ci.meta.JavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class StrictConstantReflectionFeatureLogger {

    private static final List<LogEntry> log = new ArrayList<>();

    private final ResolvedJavaMethod logMethod;
    private final BytecodeStream bytecode;

    public StrictConstantReflectionFeatureLogger(ResolvedJavaMethod logMethod) {
        this.logMethod = logMethod;
        this.bytecode = new BytecodeStream(logMethod.getCode());
        moveToNextTargeted();
    }

    private void moveToNextTargeted() {
        while (bytecode.currentBCI() != bytecode.endBCI()) {
            int opcode = bytecode.currentBC();
            if (Arrays.asList(Bytecodes.INVOKEVIRTUAL, Bytecodes.INVOKESTATIC, Bytecodes.INVOKESPECIAL, Bytecodes.INVOKEINTERFACE, Bytecodes.INVOKEDYNAMIC).contains(opcode)) {
                return;
            }
            bytecode.next();
        }
    }

    public void moveToNextInvocation() {
        bytecode.next();
        moveToNextTargeted();
    }

    public void createLogEntry(Object result) {
        StackTraceElement location = logMethod.asStackTraceElement(bytecode.currentBCI());
        int opcode = bytecode.currentBC();
        int cpi = opcode == Bytecodes.INVOKEDYNAMIC ? bytecode.readCPI4() : bytecode.readCPI();
        JavaMethod targetMethod = logMethod.getConstantPool().lookupMethod(cpi, opcode);
        LogEntry entry = new LogEntry(location, targetMethod, result);
        log.add(entry);
    }

    public static void dumpLog(String location) {
        try (JsonWriter out = new JsonPrettyWriter(Path.of(location))) {
            try (JsonBuilder.ArrayBuilder arrayBuilder = out.arrayBuilder()) {
                for (LogEntry entry : log) {
                    try (JsonBuilder.ObjectBuilder objectBuilder = arrayBuilder.nextEntry().object()) {
                        entry.toJson(objectBuilder);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private record LogEntry(StackTraceElement location, JavaMethod targetMethod, Object result) {

        public void toJson(JsonBuilder.ObjectBuilder builder) throws IOException {
                builder.append("location", location);
                builder.append("targetMethod", targetMethod.format("%H.%n(%p)"));
                builder.append("result", result instanceof Object[] resultArray ? Arrays.toString(resultArray) : result);
            }
        }
}
