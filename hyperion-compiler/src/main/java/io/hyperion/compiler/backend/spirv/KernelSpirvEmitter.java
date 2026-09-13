package io.hyperion.compiler.backend.spirv;

import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.IrType;
import io.hyperion.core.ir.IrValue;
import io.hyperion.core.ir.Terminator;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.hyperion.compiler.backend.spirv.SpirvOps.*;

/**
 * Lowers a Hyperion {@link IrFunction} into a real SPIR-V 1.5 compute
 * shader: a single-invocation ({@code local_size = 1,1,1}) kernel that
 * reads its parameters from a readonly storage buffer (one float/int per
 * parameter, by index) and writes its result to a writeonly storage buffer
 * at index 0 — the binding layout Phase 5's descriptor-set code will bind
 * real device buffers to.
 *
 * <h2>Scope (Phase 4 baseline)</h2>
 * Straight-line kernels only (single basic block — the shape every Phase 2
 * kernel and the non-branching Phase 3 kernels produce) with {@code F32}
 * or {@code I32} values. Multi-block kernels (real branches/loops) need
 * SPIR-V's structured control flow ({@code OpSelectionMerge}/
 * {@code OpLoopMerge} plus {@code OpPhi}) and are deliberately deferred
 * rather than emitted half-correct. {@code F64} is also deferred: it
 * requires declaring the {@code Float64} capability, not yet done here.
 *
 * <h2>Structural shape</h2>
 * Modeled on a real glslang-compiled compute shader with SSBO I/O that was
 * compiled and confirmed {@code spirv-val}-valid first (see the README's
 * Phase 4 section), using the modern (SPIR-V 1.3+) {@code StorageBuffer}
 * storage class + {@code Block} decoration rather than legacy
 * {@code Uniform}+{@code BufferBlock}.
 */
public final class KernelSpirvEmitter {

    public static final class UnsupportedIrShapeException extends RuntimeException {
        public UnsupportedIrShapeException(String message) { super(message); }
    }

    private final SpirvWriter w = new SpirvWriter();
    private final Map<String, Integer> typeCache = new HashMap<>();
    private final Map<String, Integer> constCache = new HashMap<>();
    private final Map<String, Integer> elemPtrCache = new HashMap<>();
    private final Map<IrValue, Integer> valueIds = new HashMap<>();

    private int floatType, intType;
    private int inputVar, outputVar;
    private int inputElemType, outputElemType;

    private KernelSpirvEmitter() {}

    public static byte[] emit(IrFunction fn) {
        return new KernelSpirvEmitter().emitInternal(fn);
    }

    private byte[] emitInternal(IrFunction fn) {
        if (fn.blocks.size() != 1) {
            throw new UnsupportedIrShapeException(
                    "Phase 4 baseline only lowers single-block (straight-line) kernels; "
                            + fn.name + " has " + fn.blocks.size() + " blocks. Multi-block "
                            + "(branch/loop) SPIR-V lowering needs OpSelectionMerge/OpLoopMerge + "
                            + "OpPhi and is a deliberately deferred follow-up.");
        }
        for (IrValue.ParamValue p : fn.params) {
            requireSupportedScalarType(p.type, fn.name);
        }
        requireSupportedScalarType(fn.returnType, fn.name);
        IrType paramElemType = requireHomogeneousParamType(fn);

        w.emit(w.capabilities, OpCapability, Capability_Shader);
        w.emit(w.memoryModel, OpMemoryModel, AddressingModel_Logical, MemoryModel_GLSL450);

        int voidType = typeVoid();
        floatType = typeFloat();
        intType = typeInt();
        int fnType = typeFunction(voidType);

        int mainId = w.freshId();

        inputElemType = (paramElemType == IrType.F32) ? floatType : intType;
        outputElemType = (fn.returnType == IrType.F32) ? floatType : intType;
        inputVar = buildBuffer("InputBuffer", "inputs", 0, true, inputElemType);
        outputVar = buildBuffer("OutputBuffer", "outputs", 1, false, outputElemType);

        emitEntryPoint(mainId, fn.name, inputVar, outputVar);
        w.emit(w.executionModes, OpExecutionMode, mainId, ExecutionMode_LocalSize, 1, 1, 1);
        w.emitWithString(w.debugNames, OpName, new int[] {mainId}, fn.name);

        // ---- function body ----
        w.emit(w.functions, OpFunction, voidType, mainId, 0 /* FunctionControl: None */, fnType);
        w.emit(w.functions, OpLabel, w.freshId());

        int zeroConst = intConst(0);
        for (IrValue.ParamValue p : fn.params) {
            int idxConst = intConst(p.paramIndex);
            int elemPtrType = elemPtrType(inputVar, inputElemType);
            int ptr = w.freshId();
            w.emit(w.functions, OpAccessChain, elemPtrType, ptr, inputVar, zeroConst, idxConst);
            int loaded = w.freshId();
            w.emit(w.functions, OpLoad, inputElemType, loaded, ptr);
            valueIds.put(p, loaded);
        }

        var block = fn.blocks.get(0);
        for (IrValue v : block.owned) {
            lowerValue(v);
        }
        if (!(block.terminator instanceof Terminator.Return ret)) {
            throw new UnsupportedIrShapeException("Single-block kernel must end in a return");
        }
        int resultId = idOf(ret.value());
        int outPtrType = elemPtrType(outputVar, outputElemType);
        int outPtr = w.freshId();
        w.emit(w.functions, OpAccessChain, outPtrType, outPtr, outputVar, zeroConst, zeroConst);
        w.emit(w.functions, OpStore, outPtr, resultId);
        w.emit(w.functions, OpReturn);
        w.emit(w.functions, OpFunctionEnd);

        return w.toBytes();
    }

    private static void requireSupportedScalarType(IrType t, String fnName) {
        if (t != IrType.F32 && t != IrType.I32) {
            throw new UnsupportedIrShapeException(
                    "Phase 4 baseline only supports F32/I32 values; " + fnName + " uses " + t
                            + " (F64 needs the Float64 capability, not yet declared)");
        }
    }

    private static IrType requireHomogeneousParamType(IrFunction fn) {
        if (fn.params.isEmpty()) {
            return IrType.F32; // no params -> input buffer type is moot, default arbitrarily
        }
        IrType first = fn.params.get(0).type;
        for (IrValue.ParamValue p : fn.params) {
            if (p.type != first) {
                throw new UnsupportedIrShapeException(
                        "Phase 4 baseline requires all kernel parameters to share one scalar type "
                                + "(single homogeneous input buffer); " + fn.name + " mixes " + first
                                + " and " + p.type);
            }
        }
        return first;
    }

    private void lowerValue(IrValue v) {
        if (v instanceof IrValue.ParamValue) {
            return; // already bound while loading parameters above
        }
        if (v instanceof IrValue.ConstValue c) {
            int id = c.type == IrType.F32 ? floatConst((float) c.value) : intConst((int) c.value);
            valueIds.put(v, id);
            return;
        }
        if (v instanceof IrValue.BinOpValue bin) {
            int left = idOf(bin.left);
            int right = idOf(bin.right);
            boolean isFloat = bin.type == IrType.F32;
            int opcode = switch (bin.op) {
                case "+" -> isFloat ? OpFAdd : OpIAdd;
                case "-" -> isFloat ? OpFSub : OpISub;
                case "*" -> isFloat ? OpFMul : OpIMul;
                case "/" -> isFloat ? OpFDiv : OpSDiv;
                default -> throw new UnsupportedIrShapeException("Unknown op: " + bin.op);
            };
            int resultType = isFloat ? floatType : intType;
            int id = w.freshId();
            w.emit(w.functions, opcode, resultType, id, left, right);
            valueIds.put(v, id);
            return;
        }
        throw new UnsupportedIrShapeException(
                "Phase 4 baseline cannot lower value kind: " + v.getClass().getSimpleName()
                        + " (phi nodes require the multi-block lowering path, not yet implemented)");
    }

    private int idOf(IrValue v) {
        IrValue resolved = v.resolve();
        Integer id = valueIds.get(resolved);
        if (id == null) {
            throw new UnsupportedIrShapeException("No SPIR-V id computed yet for value " + resolved);
        }
        return id;
    }

    // ---- type/constant helpers (cached, matching how a real compiler deduplicates) ----

    private int typeVoid() {
        return typeCache.computeIfAbsent("void", k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpTypeVoid, id);
            return id;
        });
    }

    private int typeFloat() {
        return typeCache.computeIfAbsent("f32", k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpTypeFloat, id, 32);
            return id;
        });
    }

    private int typeInt() {
        return typeCache.computeIfAbsent("i32", k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpTypeInt, id, 32, 1);
            return id;
        });
    }

    private int typeFunction(int returnType) {
        return typeCache.computeIfAbsent("fn()->" + returnType, k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpTypeFunction, id, returnType);
            return id;
        });
    }

    private int intConst(int value) {
        return constCache.computeIfAbsent("i32:" + value, k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpConstant, typeInt(), id, value);
            return id;
        });
    }

    private int floatConst(float value) {
        int bits = Float.floatToRawIntBits(value);
        return constCache.computeIfAbsent("f32:" + bits, k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpConstant, typeFloat(), id, bits);
            return id;
        });
    }

    private int elemPtrType(int bufferVar, int elemType) {
        return elemPtrCache.computeIfAbsent(bufferVar + ":" + elemType, k -> {
            int id = w.freshId();
            w.emit(w.typesConstsVars, OpTypePointer, id, StorageClass_StorageBuffer, elemType);
            return id;
        });
    }

    /**
     * Declares one storage-buffer-backed {@code struct { float data[]; }}
     * global and returns its variable id. Structural shape confirmed
     * against a real glslang-compiled, {@code spirv-val}-validated
     * reference module before writing this (see README's Phase 4 section).
     */
    private int buildBuffer(String structDebugName, String varDebugName, int binding, boolean readonly, int elemType) {
        int runtimeArrType = w.freshId();
        w.emit(w.typesConstsVars, OpTypeRuntimeArray, runtimeArrType, elemType);
        w.emit(w.decorations, OpDecorate, runtimeArrType, Decoration_ArrayStride, 4);

        int structType = w.freshId();
        w.emit(w.typesConstsVars, OpTypeStruct, structType, runtimeArrType);
        w.emit(w.decorations, OpDecorate, structType, Decoration_Block);
        w.emit(w.decorations, OpMemberDecorate, structType, 0, Decoration_Offset, 0);
        w.emit(w.decorations, OpMemberDecorate, structType, 0,
                readonly ? Decoration_NonWritable : Decoration_NonReadable);

        int ptrType = w.freshId();
        w.emit(w.typesConstsVars, OpTypePointer, ptrType, StorageClass_StorageBuffer, structType);

        int var = w.freshId();
        w.emit(w.typesConstsVars, OpVariable, ptrType, var, StorageClass_StorageBuffer);
        w.emit(w.decorations, OpDecorate, var, Decoration_DescriptorSet, 0);
        w.emit(w.decorations, OpDecorate, var, Decoration_Binding, binding);

        w.emitWithString(w.debugNames, OpName, new int[] {structType}, structDebugName);
        w.emitWithString(w.debugNames, OpName, new int[] {var}, varDebugName);

        return var;
    }

    /**
     * {@code OpEntryPoint}'s operand shape is {@code [ExecutionModel, Id,
     * LiteralString, Id...]} — the literal string sits in the middle, not
     * at the end, so it needs its own encoder rather than reusing
     * {@link SpirvWriter#emitWithString}.
     */
    private void emitEntryPoint(int mainId, String name, int... interfaceIds) {
        List<Integer> section = w.entryPoints;
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        int stringWords = utf8.length / 4 + 1;
        int wordCount = 1 + 1 + 1 + stringWords + interfaceIds.length;
        section.add((wordCount << 16) | (OpEntryPoint & 0xFFFF));
        section.add(ExecutionModel_GLCompute);
        section.add(mainId);
        int i = 0;
        while (i < utf8.length) {
            int wd = 0;
            for (int b = 0; b < 4 && i < utf8.length; b++, i++) wd |= (utf8[i] & 0xFF) << (8 * b);
            section.add(wd);
        }
        if (utf8.length % 4 == 0) section.add(0);
        for (int id : interfaceIds) section.add(id);
    }
}
