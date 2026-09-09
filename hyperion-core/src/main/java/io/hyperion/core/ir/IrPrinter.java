package io.hyperion.core.ir;

public final class IrPrinter {
    private IrPrinter() {}

    public static String print(IrFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("fn ").append(fn.name).append("(");
        for (int i = 0; i < fn.params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fn.params.get(i).name).append(": ").append(fn.params.get(i).type);
        }
        sb.append(") -> ").append(fn.returnType).append(" {\n");
        for (BasicBlock block : fn.blocks) {
            sb.append("  b").append(block.id).append(":");
            if (!block.predecessors.isEmpty()) {
                sb.append("   ; preds = ");
                for (int i = 0; i < block.predecessors.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("b").append(block.predecessors.get(i).id);
                }
            }
            sb.append("\n");
            for (IrValue v : block.owned) {
                if (v instanceof IrValue.PhiValue phi && phi.replacement != null) {
                    continue; // collapsed trivial phis are noise in the printed form
                }
                sb.append("    ").append(v.render()).append("\n");
            }
            sb.append("    ").append(block.terminator).append("\n");
        }
        sb.append("}");
        return sb.toString();
    }
}
