rootProject.name = "hyperion-gpu"

// Modules are added to this list only once real, compiling code exists for
// them — no empty placeholder modules. Uncomment/add as each phase lands:
include(
    "hyperion-runtime-vulkan",  // Phase 0 — DONE
    "hyperion-core",            // Phase 1 — DONE, Phase 2 — DONE
    "hyperion-api"              // Phase 2 — DONE (annotations)
    // "hyperion-compiler"      // Phase 3-4 — SSA IR, SPIR-V backend
    // "hyperion-kernels"       // Phase 6/10 — GEMM, FlashAttention-2, RMSNorm, RoPE
    // "hyperion-inference"     // Phase 10 — GGUF loader + inference engine
    // "hyperion-benchmarks"    // Phase 10 — JMH suite
)
