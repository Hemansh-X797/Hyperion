package io.hyperion.core.ir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base type for every SSA value in Hyperion IR. Each value is defined
 * exactly once (the "single assignment" in SSA) and carries a unique id
 * for printing ({@code v0}, {@code v1}, ...).
 *
 * {@link PhiValue} is the one mutable case: during construction a phi may
 * exist as an empty placeholder (an "incomplete phi", per Braun et al.,
 * "Simple and Efficient Construction of Static Single Assignment Form",
 * 2013) before its incoming operands are known, and may later be found
 * trivial (all operands equal) and collapsed to its single real operand.
 * Any code that reads an {@code IrValue} obtained earlier in construction
 * must call {@link #resolve()} to follow that collapse rather than assume
 * object identity is final — see {@link SsaConstructor}.
 */
public abstract sealed class IrValue permits IrValue.ParamValue, IrValue.ConstValue, IrValue.BinOpValue, IrValue.PhiValue {

    public final int id;
    public final IrType type;

    protected IrValue(int id, IrType type) {
        this.id = id;
        this.type = type;
    }

    /** Follows phi-collapse indirection to the final, non-trivial value. Identity for all non-phi values. */
    public IrValue resolve() {
        return this;
    }

    public abstract String render();

    @Override
    public String toString() {
        return "v" + id;
    }

    public static final class ParamValue extends IrValue {
        public final int paramIndex;
        public final String name;

        public ParamValue(int id, IrType type, int paramIndex, String name) {
            super(id, type);
            this.paramIndex = paramIndex;
            this.name = name;
        }

        @Override
        public String render() {
            return "v" + id + " = param " + name + " : " + type;
        }
    }

    public static final class ConstValue extends IrValue {
        public final double value;

        public ConstValue(int id, IrType type, double value) {
            super(id, type);
            this.value = value;
        }

        @Override
        public String render() {
            return "v" + id + " = const " + value + " : " + type;
        }
    }

    public static final class BinOpValue extends IrValue {
        public final String op; // "+" "-" "*" "/"
        public final IrValue left;
        public final IrValue right;

        public BinOpValue(int id, IrType type, String op, IrValue left, IrValue right) {
            super(id, type);
            this.op = op;
            this.left = left;
            this.right = right;
        }

        @Override
        public String render() {
            return "v" + id + " = " + left.resolve() + " " + op + " " + right.resolve() + " : " + type;
        }
    }

    /**
     * An SSA phi node: "the value is {@code operands.get(pred)} depending on
     * which predecessor control arrived from". Constructed empty
     * ("incomplete") when a loop header is read before all its predecessors
     * are known to be value-complete, then filled in by
     * {@link SsaConstructor} once sealed.
     */
    public static final class PhiValue extends IrValue {
        public final BasicBlock block;
        public final Map<BasicBlock, IrValue> operands = new LinkedHashMap<>();
        /** Non-null once this phi was found trivial and collapsed to a single real value. */
        public IrValue replacement;

        public PhiValue(int id, IrType type, BasicBlock block) {
            super(id, type);
            this.block = block;
        }

        @Override
        public IrValue resolve() {
            return replacement != null ? replacement.resolve() : this;
        }

        @Override
        public String render() {
            if (replacement != null) {
                return "v" + id + " = phi(collapsed -> " + replacement.resolve() + ")";
            }
            StringBuilder sb = new StringBuilder("v").append(id).append(" = phi(");
            boolean first = true;
            for (var e : operands.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append("b").append(e.getKey().id).append(": ").append(e.getValue().resolve());
            }
            return sb.append(") : ").append(type).toString();
        }
    }
}
