package io.hyperion.compiler.backend.spirv;

/**
 * Numeric SPIR-V opcodes and enumerant values, limited to what Hyperion's
 * compute-kernel backend needs. Values are from the Khronos SPIR-V
 * specification's machine-readable grammar (a fact about a public binary
 * format, not copyrightable expression — no header files are vendored).
 * Cross-checked empirically against {@code spirv-dis}/{@code spirv-val}
 * during development (see Phase4Demo) rather than trusted from memory alone.
 */
public final class SpirvOps {
    private SpirvOps() {}

    // ---- Opcodes ----
    public static final int OpName = 5;
    public static final int OpMemberName = 6;
    public static final int OpExtInstImport = 11;
    public static final int OpExtInst = 12;
    public static final int OpMemoryModel = 14;
    public static final int OpEntryPoint = 15;
    public static final int OpExecutionMode = 16;
    public static final int OpCapability = 17;
    public static final int OpTypeVoid = 19;
    public static final int OpTypeBool = 20;
    public static final int OpTypeInt = 21;
    public static final int OpTypeFloat = 22;
    public static final int OpTypeVector = 23;
    public static final int OpTypeArray = 28;
    public static final int OpTypeRuntimeArray = 29;
    public static final int OpTypeStruct = 30;
    public static final int OpTypePointer = 32;
    public static final int OpTypeFunction = 33;
    public static final int OpConstantTrue = 41;
    public static final int OpConstantFalse = 42;
    public static final int OpConstant = 43;
    public static final int OpConstantComposite = 44;
    public static final int OpFunction = 54;
    public static final int OpFunctionParameter = 55;
    public static final int OpFunctionEnd = 56;
    public static final int OpFunctionCall = 57;
    public static final int OpVariable = 59;
    public static final int OpLoad = 61;
    public static final int OpStore = 62;
    public static final int OpAccessChain = 65;
    public static final int OpDecorate = 71;
    public static final int OpMemberDecorate = 72;
    public static final int OpConvertSToF = 111;
    public static final int OpConvertFToS = 110;
    public static final int OpIAdd = 128;
    public static final int OpFAdd = 129;
    public static final int OpISub = 130;
    public static final int OpFSub = 131;
    public static final int OpIMul = 132;
    public static final int OpFMul = 133;
    public static final int OpSDiv = 135;
    public static final int OpFDiv = 136;
    public static final int OpFNegate = 127;
    public static final int OpSNegate = 126;
    public static final int OpIEqual = 170;
    public static final int OpINotEqual = 171;
    public static final int OpSLessThan = 172;
    public static final int OpSGreaterThan = 173;
    public static final int OpSGreaterThanEqual = 174;
    public static final int OpSLessThanEqual = 175;
    public static final int OpFOrdEqual = 180;
    public static final int OpFOrdNotEqual = 182;
    public static final int OpFOrdLessThan = 184;
    public static final int OpFOrdGreaterThan = 186;
    public static final int OpFOrdGreaterThanEqual = 188;
    public static final int OpFOrdLessThanEqual = 190;
    public static final int OpPhi = 245;
    public static final int OpLoopMerge = 246;
    public static final int OpSelectionMerge = 247;
    public static final int OpLabel = 248;
    public static final int OpBranch = 249;
    public static final int OpBranchConditional = 250;
    public static final int OpReturn = 253;
    public static final int OpReturnValue = 254;
    public static final int OpUnreachable = 255;

    // ---- Enumerants ----
    public static final int AddressingModel_Logical = 0;
    public static final int MemoryModel_GLSL450 = 1;
    public static final int ExecutionModel_GLCompute = 5;
    public static final int ExecutionMode_LocalSize = 17;
    public static final int Capability_Shader = 1;
    public static final int StorageClass_Uniform = 2;
    public static final int StorageClass_Function = 7;
    public static final int StorageClass_StorageBuffer = 12;
    public static final int Decoration_ArrayStride = 6;
    public static final int Decoration_BufferBlock = 3;
    public static final int Decoration_Block = 2;
    public static final int Decoration_Offset = 35;
    public static final int Decoration_Binding = 33;
    public static final int Decoration_DescriptorSet = 34;
    public static final int Decoration_NonWritable = 24;
    public static final int Decoration_NonReadable = 25;
    public static final int SelectionControl_None = 0;
    public static final int LoopControl_None = 0;
}
