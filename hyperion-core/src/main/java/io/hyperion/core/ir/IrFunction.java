package io.hyperion.core.ir;

import java.util.List;

public final class IrFunction {
    public final String name;
    public final List<IrValue.ParamValue> params;
    public final IrType returnType;
    public final List<BasicBlock> blocks; // in program (bytecode) order
    public final BasicBlock entry;

    public IrFunction(String name, List<IrValue.ParamValue> params, IrType returnType,
                       List<BasicBlock> blocks, BasicBlock entry) {
        this.name = name;
        this.params = params;
        this.returnType = returnType;
        this.blocks = blocks;
        this.entry = entry;
    }
}
