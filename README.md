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
| **4** | SPIR-V Binary Bytecode Generator | ✅ **Done** — see below |
| **5** | Vulkan Compute Pipeline & Command Queue Engine | ✅ **Done** — see below |
| **6** | GPU Kernel Optimizations | ✅ **Done** (loop unrolling + DCE — see below) |
| **7** | CPU Heterogeneous Execution & SIMD Fallback | ✅ **Done** — see below |
| **8** | Virtual Thread Task Scheduler | ✅ **Done** — see below |
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

## Phase 4 — Direct SPIR-V Binary Bytecode Generator

`hyperion-compiler/backend/spirv` lowers straight-line SSA IR into a real
SPIR-V 1.5 binary module — no glslang, no clang, a hand-written binary
writer emitting the actual opcode words.

- **Reference-first methodology:** before writing a single opcode, I
  compiled a real GLSL compute shader with storage-buffer I/O through
  `glslangValidator`, validated it with `spirv-val`, and disassembled it
  with `spirv-dis` — giving a byte-proven-correct structural target rather
  than reconstructing the spec from memory. Our emitter uses the modern
  (SPIR-V 1.3+) `StorageBuffer` storage class + `Block` decoration rather
  than the legacy `Uniform`+`BufferBlock` style that older glslang output
  still uses — both are valid; this is the currently-recommended form.
- **`SpirvWriter.java`** — raw word/section/string encoding matching the
  binary container format exactly (magic number, version, bound, the
  required section ordering, `(wordCount<<16)|opcode` instruction headers,
  UTF-8 strings packed 4 bytes/word with NUL termination).
- **`KernelSpirvEmitter.java`** — lowers an `IrFunction` into a
  single-invocation compute shader: kernel parameters read by index from a
  readonly storage buffer, the SSA `BinOp` chain translated directly to
  `OpFAdd`/`OpFMul`/`OpIAdd`/etc. (no `OpVariable`+`Load`/`Store`
  indirection for locals — we're SSA already, so this is closer to what a
  real optimizer produces after mem2reg than glslang's raw variable-based
  output), result written to a writeonly output buffer.
- **Honest, explicit scope boundary:** straight-line (single-block)
  kernels only, `F32`/`I32` only. Multi-block kernels are *rejected*, not
  silently mishandled — real SPIR-V control flow needs `OpSelectionMerge`/
  `OpLoopMerge`/`OpPhi` structured-CFG lowering, deferred rather than
  emitted half-correct. `F64` is rejected too (needs the `Float64`
  capability declaration, not yet added).

**Proof — the real Khronos validator, not our own reading of the spec:**
`fma`, `quadratic` (both F32), and `dotProduct2` (I32) were compiled from
real Java bytecode all the way to SPIR-V bytes and run through the actual
`spirv-val` binary. One real bug was caught and fixed this way: the buffer
element type was initially hardcoded to `float`, and `spirv-val` correctly
rejected `dotProduct2`'s int buffer access with a precise type-mismatch
error — fixed by deriving the buffer's element type from the kernel's own
parameter type. All three kernels now pass:

```
--- fma ---
Emitted 840 bytes (210 words) of real SPIR-V
[spirv-val] VALID
--- quadratic ---
Emitted 972 bytes (243 words) of real SPIR-V
[spirv-val] VALID
--- dotProduct2 ---
Emitted 932 bytes (233 words) of real SPIR-V
[spirv-val] VALID
```

`spirv-dis` output for `fma` (real disassembly of our own byte output):

```
       %fma = OpFunction %void None %4
         %14 = OpLabel
         %17 = OpAccessChain %_ptr_StorageBuffer_float %inputs %int_0 %int_0
         %18 = OpLoad %float %17
         %20 = OpAccessChain %_ptr_StorageBuffer_float %inputs %int_0 %int_1
         %21 = OpLoad %float %20
         %23 = OpAccessChain %_ptr_StorageBuffer_float %inputs %int_0 %int_2
         %24 = OpLoad %float %23
         %25 = OpFMul %float %18 %21
         %26 = OpFAdd %float %25 %24
         %28 = OpAccessChain %_ptr_StorageBuffer_float_0 %outputs %int_0 %int_0
               OpStore %28 %26
               OpReturn
               OpFunctionEnd
```

`KernelSpirvEmitterTest` also asserts the raw magic number/version words
and that multi-block/F64 kernels are rejected rather than silently
mis-lowered — both confirmed by manually executing the same assertions
against the compiled classes (no Maven Central access in this sandbox for
the JUnit runtime itself, same caveat as every phase's test file).

**Requires `spirv-tools` and `glslang-tools`** (`apt install spirv-tools
glslang-tools`) to run the validator/disassembler steps — Phase 5's actual
GPU dispatch, combined with this phase's SPIR-V and Phase 0's Vulkan FFM
bindings, is the natural place to prove these kernels don't just validate
but *execute correctly on a real device* end-to-end.

## Phase 5 — Vulkan Compute Pipeline & Command Queue Engine (the payoff phase)

`hyperion-runtime-vulkan/pipeline` is where Phase 0's Vulkan FFM bindings
and Phase 4's SPIR-V emitter meet for the first time — a Hyperion kernel
runs on a real device and its answer is checked against real Java
execution, completing the full pipeline: **Java bytecode → SSA IR → SPIR-V
→ real GPU dispatch → readback**, no step simulated.

- **21 new struct layouts** (`DeviceStructLayouts.java`) — logical device
  creation, buffers, memory requirements/allocation, descriptor sets,
  pipeline/shader-stage, command pool/buffer, submit info. Every single one
  was empirically verified against hand-derived x86-64 ABI byte sizes
  before use (same discipline as Phase 0's `StructLayoutSizeTest`) — **all
  21 matched on the first attempt**, no padding bugs this time.
- **~20 new FFM bindings** (`VulkanFFM.java`) — `vkCreateDevice` through
  `vkQueueSubmit`/`vkDeviceWaitIdle`, the full device/buffer/descriptor/
  pipeline/command-buffer surface.
- **`ComputeDispatcher.java`** — creates a logical device + compute queue,
  two host-visible+host-coherent storage buffers (input params packed by
  index, output result), a descriptor set layout matching the binding=0/1
  layout `KernelSpirvEmitter` already committed to in Phase 4, a compute
  pipeline from the real SPIR-V bytes, records one `dispatch(1,1,1)`,
  submits, waits, and reads the result back. Host-visible memory used
  directly (no staging buffer) — correct and simple for scalar kernel
  inputs; real tensor-sized dispatch would stage through device-local
  memory, a Phase 6/8 concern.

**Proof — the full pipeline, working first try:** `fma`, `quadratic`, and
`dotProduct2` were compiled from real Java bytecode all the way to SPIR-V
(Phase 4), dispatched on the real `llvmpipe` Vulkan device from Phase 0,
and their GPU-computed answers checked against genuinely invoking the
compiled Java method:

```
Dispatching on: llvmpipe (LLVM 20.1.2, 256 bits) [CPU (software rasterizer)]

--- fma[2.0, 3.0, 4.0] ---
  GPU result:  10.0
  Java result: 10.0
  [MATCH]
--- quadratic[2.0, 1.0, -3.0, 5.0] ---
  GPU result:  3.0
  Java result: 3.0
  [MATCH]
--- dotProduct2[3, 4, 5, 6] ---
  GPU result:  39.0
  Java result: 39.0
  [MATCH]
```

Not satisfied with one lucky run, a 30-iteration stress test with random
float inputs (seeded, reproducible) ran repeated dispatches through the
**same** `ComputeDispatcher`/device — checking for state leaks across
dispatches as much as numeric correctness. **30/30 exact matches** to full
float precision, e.g. `a=-69.7937 b=-89.4842 c=66.7732 → gpu=6312.2061
java=6312.2061`.

## Phase 6 — GPU Kernel Optimizations (loop unrolling + dead code elimination)

`hyperion-compiler/passes` adds real IR-to-IR transformations — and one of
them does something more interesting than "make it faster": it **unlocks
GPU dispatch for a whole class of kernels the backend previously rejected
outright**.

- **`LoopUnrollerPass.java`** — fully unrolls a counted loop with a
  compile-time-constant trip count via genuine SSA value substitution (not
  a re-execution shortcut): identifies the counter phi (constant initial
  value, constant per-iteration step), requires the header's comparison
  bound to also be a compile-time constant, computes an exact trip count,
  then symbolically replays the loop body that many times — cloning each
  `BinOp` with its operands resolved through a substitution map that
  threads the "current iteration's" phi values through, exactly like hand
  loop-unrolling but done on the SSA graph itself. A real bug surfaced and
  got fixed here too: javac's actual bytecode for `for (i < 4)` compiles
  the *inverted* test `if (i >= 4) goto exit` with fallthrough to the body
  — not the naive direct encoding I assumed — caught immediately by
  running against real compiled bytecode and fixed by normalizing the
  branch direction before pattern-matching the comparison.
- **`DeadCodeEliminationPass.java`** — sound and complete for this IR
  (every value kind is pure, so "reachable from the terminator" is a
  correct liveness definition, no aliasing analysis needed) — sweeps away
  the now-unused loop-counter arithmetic left behind after unrolling.
- **Honest, explicit scope boundary:** only exactly the 4-block
  preheader/header/body/exit shape `SsaConstructor` produces for a simple
  loop, and only when the trip count is a compile-time constant. A
  data-dependent bound like `sumTo(int n)` (`n` is a parameter, not a
  constant) is *correctly refused*, not silently mis-unrolled — proven by
  running it through the pass and confirming it throws.

**Proof — the unlock, demonstrated live:** `integrateFixed` (a 4-step fixed
Euler integration, `x = x + dx*x` repeated 4 times — a real pattern for
bounded numerical solvers) was first fed to Phase 4's SPIR-V emitter as-is
and **correctly rejected** (multi-block). After running it through
`LoopUnrollerPass` + `DeadCodeEliminationPass`, the same kernel — same
semantics, verified against real Java execution across a 30-point sweep of
`(x0, dx)` pairs — became a single straight-line block, compiled to real
SPIR-V, and **dispatched on the actual `llvmpipe` GPU device**, matching
Java's float32 result *exactly* (not just within tolerance, since both the
GPU and Java use genuine float32 arithmetic the whole way through):

```
--- Before unrolling (4 blocks) ---
  b1: if v3 >= v4 then b3 else b2      ; the inverted encoding, caught live
--- After unrolling + DCE (1 block) ---
  b0: v11 = v1 * v0 : F32
      v12 = v0 + v11 : F32
      v14 = v1 * v12 : F32 ...          ; 4 clean unrolled iterations, no loop-counter junk

[expected] SPIR-V backend correctly rejects the loop-bearing kernel: ...
Emitted 928 bytes of SPIR-V from a kernel that had a loop 30 seconds ago.
  GPU dispatch: x0=1.0 dx=0.1  -> gpu=1.4641001224517822 java=1.4641001224517822 [MATCH]
  GPU dispatch: x0=2.0 dx=-0.05 -> gpu=1.6290124654769897 java=1.6290124654769897 [MATCH]

--- Honesty check: refusing to unroll a data-dependent loop bound ---
[ok] correctly refused: Header comparison in sumTo does not compare the
     counter phi against a compile-time constant bound ...
```

## Phase 7 — CPU Heterogeneous Execution & SIMD Fallback (including a real negative result)

`hyperion-compiler/backend/vector` executes straight-line kernels over a
batch of inputs using real hardware SIMD via `jdk.incubator.vector` —
confirmed running on genuine **AVX-512 hardware** in this environment
(`FloatVector.SPECIES_PREFERRED` = 16-lane, 512-bit registers, verified via
`lscpu`, not assumed).

- **`VectorKernelExecutor.java`** — walks the kernel's SSA graph once per
  lane-width chunk (16 elements at a time here), with every value being a
  `FloatVector` instead of a scalar — one Java call computes 16 outputs at
  once. A scalar tail loop handles the remainder when the batch isn't a
  multiple of the lane width.
- **Correctness — clean pass:** a deliberately non-lane-aligned batch of
  1,000,003 `fma` elements (16 doesn't divide it, forcing both the
  vectorized *and* scalar-tail code paths to run) matched real scalar Java
  execution **bit-for-bit exactly, 0 mismatches out of 1,000,003**.

### The honest part: the naive SIMD path is *slower* than scalar, and here's why

The first working version used a `HashMap<IrValue, FloatVector>` per
16-element chunk — allocating and hashing on every chunk completely
dominated any SIMD benefit (0.04x — 25x *slower* than a plain scalar
loop). Real, measured, not a guess. Fixed by replacing it with an
array-indexed register file (`FloatVector[]`, indexed by value id, reused
across all chunks, zero per-chunk allocation) — a straightforward but
real performance bug caught by actually measuring rather than assuming
"should be faster because SIMD."

After that fix, throughput on two different kernels (`quadratic`, 4 flops/
element, and a synthetic 10-multiply-add `heavyPolynomial`, 18 flops/
element):

```
quadratic (4 flops/elem):        SIMD 84.9 M elem/sec   scalar 553.1 M elem/sec   speedup 0.15x
heavyPolynomial (18 flops/elem): SIMD 36.2 M elem/sec   scalar 688.1 M elem/sec   speedup 0.05x
```

**Scalar wins, and the gap doesn't close with more arithmetic intensity —
if anything it widens.** This is a genuine, useful finding, not a bug to
paper over: HotSpot's C2 compiler already auto-vectorizes simple scalar
array loops like `scalarQuadratic` extremely well on its own (500-700M
elem/sec on plain arithmetic loops is consistent with C2's own SIMD
codegen, not scalar instructions), while `VectorKernelExecutor`'s
per-value `instanceof`-dispatch interpretation loop adds real overhead on
*every* element that C2's compiled, dispatch-free loop body never pays —
and that overhead scales with IR node count, which is exactly why the
heavier kernel's ratio got worse, not better.

**What this means for the roadmap, honestly:** this baseline is correct
but not yet a genuine "fast path" for simple kernels on hardware without a
GPU — it mainly demonstrates the mechanism (real hardware SIMD execution
of Hyperion IR) and will need a real fix before it's actually useful:
compiling each kernel shape down to a dispatch-free specialized method
(e.g. via runtime bytecode generation, mirroring Phase 4's own SPIR-V
approach but targeting a JVM method body instead) rather than
interpreting the IR graph node-by-node per chunk. Documented here instead
of quietly benchmarked-away, per this project's own standard: report the
number you actually measured.

## Phase 8 — Virtual Thread Task Scheduler

`hyperion-core/scheduler` builds a real asynchronous submission engine on
Java Virtual Threads with genuine work-stealing — not a wrapper around
`ExecutorService`, an actual implementation of the algorithm the roadmap
named.

- **`WorkStealingDeque.java`** — a real **Chase-Lev lock-free
  work-stealing deque** (Chase & Lev, SPAA 2005): the owner thread
  pushes/pops its own bottom (LIFO), any number of other threads steal
  concurrently from the top, with a growable circular array and the
  standard CAS-guarded race for the last element.
- **`HyperionScheduler.java`** — N virtual-thread workers (`Thread.ofVirtual()`),
  each owning one deque; idle workers steal from a randomly chosen sibling;
  external `submit()` calls round-robin across workers, while calls made
  from *inside* a worker push onto that worker's own deque for free
  (no contention).

**Proof — the subtle concurrency primitive, stress-tested first, in
isolation, before anything was built on top of it:** 1 owner thread
pushing/popping interleaved with **8 real thief virtual threads** racing
for 2,000,000 items — checked for the two properties that matter for a
lock-free structure (no lost items, no duplicate delivery). **5/5 clean
runs, 0 lost, 0 duplicated, every time.**

**A real concurrency-safety bug found and fixed along the way:**
`ComputeDispatcher` (Phase 5) used `Arena.ofConfined()` for its persistent
buffer-allocation arena — which only permits access from the exact thread
that created it. Since work-stealing can hand a queued GPU-dispatch task
to *any* worker, not necessarily the one that created the dispatcher, this
would have thrown `WrongThreadException` the first time a task got stolen
across threads. Fixed by switching to `Arena.ofShared()`; re-verified
Phase 5's own demo still passes correctly after the change before building
on top of it.

**Proof — real mixed heterogeneous work, actually concurrent:** two
independent logical Vulkan devices (Phase 5) plus CPU SIMD batches (Phase
7) — 80 tasks total (40 GPU dispatches split across the two devices, 40
CPU SIMD batches of 1,000-6,000 elements each) — submitted to a 4-worker
scheduler and collected via `CompletableFuture`. **0 mismatches out of 80**,
all real GPU dispatches and real SIMD executions, genuinely interleaved
and work-stolen across virtual threads:

```
Created independent logical device #0
Created independent logical device #1

Scheduler started with 4 virtual-thread workers (work-stealing Chase-Lev deques)

Submitted 80 mixed GPU+CPU tasks (40 GPU dispatches across 2 logical devices, 40 CPU SIMD batches)
All results collected in 280 ms
Mismatches: 0 / 80
```

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

## Next: say "continue" for Phase 9

Phase 9 (Multi-Backend Expansion: Triton / OpenCL) adds lowerings from
Hyperion IR to Triton's MLIR dialect and raw OpenCL C source, for
accelerator targets outside the Vulkan/SPIR-V path — the same IR this
project has been building since Phase 3, now with a third real backend
alongside SPIR-V (Phase 4) and the Vector API (Phase 7).
