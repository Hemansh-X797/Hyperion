package io.hyperion.core.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A maximal straight-line sequence of IR values ending in one
 * {@link Terminator}. Predecessor/successor edges are filled in during the
 * structural CFG pass in {@link SsaConstructor}, before any SSA value
 * construction begins.
 */
public final class BasicBlock {

    public final int id;
    public final List<IrValue> owned = new ArrayList<>(); // values defined while processing this block, in order
    public final List<BasicBlock> predecessors = new ArrayList<>();
    public final List<BasicBlock> successors = new ArrayList<>();
    public Terminator terminator;

    /** True once all predecessors are value-processing-complete and any incomplete phis here are finalized. */
    boolean sealed = false;
    int processedPredecessorCount = 0;

    /** slot -> phi placeholder, for phis created here while this block was still unsealed. */
    final Map<Integer, IrValue.PhiValue> incompletePhis = new LinkedHashMap<>();

    public BasicBlock(int id) {
        this.id = id;
    }

    public boolean isSealed() {
        return sealed;
    }

    @Override
    public String toString() {
        return "b" + id;
    }
}
