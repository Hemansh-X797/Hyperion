package io.hyperion.compiler.passes;

import io.hyperion.core.ir.*;

import java.util.*;

/**
 * Removes SSA values that nothing reads. Every value kind in Hyperion IR
 * ({@code Param}, {@code Const}, {@code BinOp}) is pure (no side effects),
 * so "reachable from the terminator, transitively through operands" is a
 * sound and complete liveness definition — no aliasing, no memory effects
 * to worry about, unlike DCE in a general-purpose compiler.
 */
public final class DeadCodeEliminationPass {

    private DeadCodeEliminationPass() {}

    public static IrFunction run(IrFunction fn) {
        List<BasicBlock> newBlocks = new ArrayList<>();
        int entryIndex = fn.blocks.indexOf(fn.entry);
        for (BasicBlock block : fn.blocks) {
            newBlocks.add(sweepBlock(block));
        }
        return new IrFunction(fn.name, fn.params, fn.returnType, newBlocks, newBlocks.get(entryIndex));
    }

    private static BasicBlock sweepBlock(BasicBlock block) {
        Set<IrValue> live = new HashSet<>();
        markLiveFromTerminator(block.terminator, live);

        List<IrValue> kept = new ArrayList<>();
        for (int i = block.owned.size() - 1; i >= 0; i--) {
            IrValue v = block.owned.get(i);
            if (v instanceof IrValue.PhiValue phi && phi.replacement != null) {
                continue; // already-collapsed trivial phi: never resurrect it
            }
            if (live.contains(v)) {
                markOperandsLive(v, live);
                kept.add(v);
            }
        }
        Collections.reverse(kept);

        BasicBlock result = new BasicBlock(block.id);
        result.owned.addAll(kept);
        result.predecessors.addAll(block.predecessors);
        result.successors.addAll(block.successors);
        result.terminator = block.terminator;
        return result;
    }

    private static void markLiveFromTerminator(Terminator t, Set<IrValue> live) {
        if (t instanceof Terminator.Return ret) {
            live.add(ret.value().resolve());
        } else if (t instanceof Terminator.CondBranch cb) {
            live.add(cb.left().resolve());
            live.add(cb.right().resolve());
        }
    }

    private static void markOperandsLive(IrValue v, Set<IrValue> live) {
        if (v instanceof IrValue.BinOpValue bin) {
            live.add(bin.left.resolve());
            live.add(bin.right.resolve());
        } else if (v instanceof IrValue.PhiValue phi) {
            for (IrValue operand : phi.operands.values()) {
                live.add(operand.resolve());
            }
        }
    }
}
