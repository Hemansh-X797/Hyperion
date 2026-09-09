package io.hyperion.core.ir;

/** How control leaves a basic block. Every block has exactly one, set once its instructions are decoded. */
public sealed interface Terminator permits Terminator.Return, Terminator.Jump, Terminator.CondBranch {

    record Return(IrValue value) implements Terminator {
        @Override
        public String toString() {
            return "return " + value.resolve();
        }
    }

    record Jump(BasicBlock target) implements Terminator {
        @Override
        public String toString() {
            return "jump b" + target.id;
        }
    }

    /** {@code cmpOp} is one of "<" "<=" ">" ">=" "==" "!=". */
    record CondBranch(String cmpOp, IrValue left, IrValue right, BasicBlock trueTarget, BasicBlock falseTarget)
            implements Terminator {
        @Override
        public String toString() {
            return "if " + left.resolve() + " " + cmpOp + " " + right.resolve()
                    + " then b" + trueTarget.id + " else b" + falseTarget.id;
        }
    }
}
