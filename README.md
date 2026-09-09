# Hyperion — Zero-Dependency Java GPU JIT Compiler & Execution Engine

Bypasses CUDA. Captures Java kernels, lowers them to SSA IR, emits raw
SPIR-V, dispatches directly to GPU hardware over Vulkan — using nothing but
the modern OpenJDK toolkit (FFM, Vector API, Loom). No JNI, no LWJGL, no
glslang, no external native glue.

## Status

| Phase | Scope | Status |
|---|---|---|
| **0** | Hardware Abstraction & Raw Vulkan FFM Bootstrapping | ✅ **Done** — see below |
| **1** | Zero-GC Off-Heap Tensor Memory Engine | ✅ **Done** — see below |
| **2** | Code Reflection & AST Capture | ✅ **Done** (stock-JDK path — see below) |
| **3** | Hyperion SSA IR | ✅ **Done** — see below |
| 4 | SPIR-V Binary Bytecode Generator | Not started |
| 5 | Vulkan Compute Pipeline & Command Queue Engine | Not started |
| 6 | GPU Kernel Optimizations | Not started |
| 7 | CPU Heterogeneous Execution & SIMD Fallback | Not started |
| 8 | Virtual Thread Task Scheduler | Not started |
| 9 | Multi-Backend Expansion (Triton / OpenCL) | Not started |
| 10 | Production Kernels, GGUF Loader, Benchmarks | Not started |

## Phase 0 — what's actually implemented and proven

`hyperion-runtime-vulkan` contains real FFM bindings to `libvulkan.so.1` /
`vulkan-1.dll` — no simulation, no mocks:

- **`ffm/VulkanConstants.java`** — numeric ABI constants (VkResult,
  VkStructureType, queue/device-type flags, `VK_MAKE_API_VERSION`).
- **`ffm/StructLayouts.java`** — hand-derived, alignment-exact
  `MemoryLayout`s for `VkApplicationInfo`, `VkInstanceCreateInfo`,
  `VkQueueFamilyProperties`, and the full 106-field `VkPhysicalDeviceLimits`
  → `VkPhysicalDeviceProperties` (824 bytes, matching real LP64 `sizeof`).
  **Important:** `MemoryLayout.structLayout(...)` in JDK 22 does **not**
  auto-insert alignment padding — every pad byte here (5 of them) was found
  by simulating the real C struct-layout algorithm and confirmed by
  `StructLayoutSizeTest`.
- **`ffm/VulkanFFM.java`** — `Linker`/`SymbolLookup` downcall handles for
  `vkCreateInstance`, `vkDestroyInstance`, `vkEnumeratePhysicalDevices`,
  `vkGetPhysicalDeviceProperties`, `vkGetPhysicalDeviceQueueFamilyProperties`,
  `vkEnumerateInstanceVersion`, with `VkResult` decoding.
- **`device/VulkanInstanceBootstrap.java`** — real `vkCreateInstance`,
  clean `vkDestroyInstance` on `close()`.
- **`device/PhysicalDeviceManager.java`** — real GPU + queue-family
  enumeration, decoded into `record`s with `supportsCompute()` etc.
- **`demo/Phase0Demo.java`** — runnable end-to-end proof.

### This was actually run, not just compiled

Against a real Vulkan loader in a Linux container (Mesa `llvmpipe` software
ICD):

```
Vulkan loader instance version: 1.3.275
VkInstance created successfully: MemorySegment{ address: 0x7f..., byteSize: 0 }
Physical devices found: 1
  - llvmpipe (LLVM 20.1.2, 256 bits) [CPU (software rasterizer)] vendor=0x10005 device=0x0000 apiVersion=1.4.318
      queueFamily[0]: count=1 graphics=true compute=true transfer=true
Phase 0 bootstrap complete, instance destroyed cleanly
```

On a machine with a real GPU + vendor driver (NVIDIA/AMD/Intel), the same
code enumerates that hardware instead — nothing changes.

## Phase 1 — what's actually implemented and proven

`hyperion-core` contains the zero-GC off-heap tensor memory engine every
later phase builds on:

- **`memory/DataType.java`** — element type registry (FLOAT32/64, INT32/64,
  INT8, BOOL8, FLOAT16, BFLOAT16) plus GGUF-style block-quantized types
  (Q4_0, Q8_0) with correct block-rounded byte sizing for Phase 10's model
  loader, ahead of when it's actually needed.
- **`memory/Shape.java`** — immutable N-D shape with row-major
  (C-order) contiguous stride derivation, NumPy-style negative-axis
  normalization.
- **`memory/OffHeapArena.java`** — tracked wrapper around `Arena` with a
  default **64-byte alignment** on every allocation (AVX-512 register width
  for Phase 7's SIMD fallback, and a safe default for Vulkan storage-buffer
  offset alignment ahead of Phase 5 querying the device's actual
  `minStorageBufferOffsetAlignment`). Confined and shared variants for
  single-thread vs. Loom/virtual-thread access patterns (Phase 8).
- **`memory/OffHeapTensor.java`** — strided tensor view: zero-copy
  `slice()`, zero-copy `transpose()`, `reshape()` (contiguous-only, correctly
  rejects non-contiguous views), `copy()` to materialize a non-contiguous
  view, typed indexed get/set per dtype.

### This was actually run and stress-tested, not just compiled

Alignment, slicing, transpose, reshape-rejection, and copy-correctness were
all run against real off-heap memory (see `Phase1Demo`, all checks passed).
Separately, to prove the "zero-GC" claim isn't just a docstring: an 800MB
tensor was allocated and touched at both ends **against a JVM heap capped at
`-Xmx512m`**. A `new float[200_000_000]` heap array at that size would have
thrown `OutOfMemoryError` immediately since it exceeds the entire heap cap.
Instead:

```
Allocated tensor of 200000000 floats = 800 MB off-heap
First = 1.5, Last = 2.5
JVM heap before: 1 MB
JVM heap after 800MB off-heap alloc: 2 MB
Heap growth: 0 MB (should be ~0, not ~800)
```

## Toolchain requirement

**JDK 24+ is required.** `java.lang.foreign` (FFM/Panama, JEP 454)
finalizes in JDK 22; `java.lang.classfile` (JEP 484, used by Phase 2's
AstExtractor) only finalizes in JDK 24. This repo's Gradle toolchain is
pinned to 24 — both APIs work unchanged there. Do not backport to JDK 21/22
by adding `--enable-preview`; several FFM method names changed between the
JDK 21 preview and the JDK 22+ final API (`allocateFrom`, `getString`,
`allocate(layout, count)`) and will not compile against 21.

## Phase 2 — Code Reflection & AST Capture (decision made: stock JDK)

The blueprint's Phase 2 called for Project Babylon's `java.lang.reflect
.code`, which — as flagged before starting this phase — **does not exist
in any shipped JDK**, only in Babylon's own early-access fork. We went with
the stock-JDK alternative: `java.lang.classfile` (JEP 484, finalized JDK
24), parsing real method bytecode directly.

**What's implemented (`hyperion-core/reflection`):**

- **`AstExtractor.java`** — symbolically executes a method's actual JVM
  bytecode operand stack (loads, constants, arithmetic operators, return)
  to reconstruct an `Expr` tree — not a decompiler heuristic, a direct
  stack-machine simulation using the classfile API's structured
  `LoadInstruction`/`ConstantInstruction`/`OperatorInstruction`/
  `ReturnInstruction` models.
- **`ast/Expr.java`** — sealed `Param`/`Const`/`BinaryOp` AST, each with a
  real `evaluate(double[])` so the tree isn't just a debug-print structure —
  it's independently executable and used to cross-check correctness.
- **Explicit, honest scope boundary:** straight-line arithmetic only
  (+ - * /, primitive params, single return). Branches, loops, field/array
  access, and calls are *detected and rejected* with a specific reason
  (`UnsupportedKernelShapeException`), not silently mis-extracted — control
  flow needs a real CFG, which is Phase 3's job.

**Proof, not just compilation:** four real `@GpuKernel` methods (FMA,
quadratic, 3-way average, 2D dot product) had their AST extracted from
actual compiled bytecode, evaluated via `Expr.evaluate()`, and cross-checked
against genuinely invoking the real method via reflection — exact numeric
match on every one. A branchy kernel and a kernel that calls `Math.abs()`
were both correctly rejected (the branchy one fails specifically because
`FCMPG` — yes, comparisons are modeled as `OperatorInstruction` too — isn't
a supported symbol, not because of a generic catch-all). A sweep test
(`AstExtractorTest`) checks ~35 input combinations of the FMA kernel against
its real bytecode execution, not just one hand-picked case.

## Phase 3 — Hyperion SSA IR (real CFG + Braun et al. SSA construction)

`hyperion-core/ir` lifts Phase 2's "no branches/loops" restriction with a
genuine control-flow graph and proper SSA construction — not a toy:

- **`SsaConstructor.java`** — two-phase build: (1) a structural pass
  partitions real bytecode into basic blocks via the standard leader
  algorithm and links predecessor/successor edges by resolving every
  `BranchInstruction`'s `Label` target; (2) a value pass walks blocks in
  bytecode program order running **Braun, Buchwald, Hack, Leißa, Mallon &
  Zwinkau's "Simple and Efficient Construction of SSA Form" (CC 2013)** —
  incomplete phis for loop headers reached via a not-yet-processed
  back-edge, sealed (finalized) once every predecessor is value-complete,
  with trivial-phi elimination via replacement indirection. No
  dominance-frontier precomputation needed.
- **`IrValue.java`** — `Param`/`Const`/`BinOp`/`Phi` SSA nodes; `Phi` is the
  one mutable node (placeholder → finalized → possibly collapsed-trivial),
  everything else is immutable once constructed.
- **`Terminator.java`** — `Return`/`Jump`/`CondBranch`, decoded from real
  `IF_ICMPxx` (2-operand int compare), `IFLT`/`IFGE`/etc. (1-operand vs.
  implicit zero), and float/double/long comparisons (`FCMPG`/`FCMPL` fused
  with the following branch — comparisons are themselves `OperatorInstruction`s
  in this API, not a separate compare-instruction family, which the code
  handles explicitly rather than assuming).
- **`IrInterpreter.java`** — genuinely *executes* the CFG (not a stateless
  tree-eval like Phase 2's `Expr` — phi selection needs to know which
  predecessor control actually arrived from), used purely to cross-check
  construction correctness against real bytecode execution.
- **Explicit, honest scope boundary:** operand stack must be empty at every
  block boundary — true for real `if`/`while`/`for` statements, false for
  stack-spanning constructs like the ternary operator, which are rejected
  rather than silently mishandled. Field/array access and method calls still
  aren't supported (unchanged from Phase 2's boundary, just now explicitly
  re-verified per-instruction rather than implied by the absence of a CFG).

**Proof:** four real kernels — `clampedRelu` and `max2` (if/else, no loop)
plus `sumTo` and `power` (for-loop and while-loop, with genuine
loop-carried phi nodes at the header) — had their SSA IR constructed from
actual compiled bytecode, printed, *interpreted* by walking the real CFG,
and cross-checked against genuinely invoking the real compiled method.
**20/20 test cases passed**, including edge cases (`n=0` — loop body never
executes; `exponent=0` — identity result). The printed IR for `sumTo`
shows exactly the loop-header phi structure the algorithm is supposed to
produce:

```
b1:   ; preds = b0, b2
  v3 = phi(b0: v2, b2: v8) : I32   ; loop counter i
  v5 = phi(b0: v1, b2: v6) : I32   ; accumulator s
  if v3 >= v0 then b3 else b2
```

`SsaConstructorTest` adds a regression sweep (`n = 0..50` against real
execution) plus structural assertions (loop header must contain a real,
non-trivial 2-operand phi).

## Running Phase 0 yourself

```bash
# Requires JDK 22+ on PATH, or edit build.gradle.kts toolchain version
./gradlew :hyperion-runtime-vulkan:run
```

Or directly:

```bash
javac -d out $(find hyperion-runtime-vulkan/src/main/java -name '*.java')
java --enable-native-access=ALL-UNNAMED -cp out io.hyperion.runtime.vulkan.demo.Phase0Demo
```

## Licensing

All original code in this repository is proprietary — see `LICENSE`. Per
the project mandate, zero non-JDK runtime dependencies are used in
`hyperion-runtime-vulkan`; the only "external" input is the Vulkan ABI
itself (numeric constants + struct layouts), which are facts about a public
standard, not copyrightable Khronos header text — no SDK headers are
vendored.

## Next: say "continue" for Phase 4

Phase 4 (Direct SPIR-V Binary Bytecode Generator) lowers this SSA IR into
real SPIR-V 1.5 compute-shader words — no glslang/clang, a binary writer
emitting the module directly, same standard: real output, validated (e.g.
via `spirv-val` if available, or by round-tripping through a disassembler)
rather than just "should be correct."
