package io.hyperion.core.ir;

import java.util.HashMap;
import java.util.Map;

/**
 * Genuinely executes an {@link IrFunction} by walking basic blocks in real
 * control-flow order (not a stateless tree-eval like Phase 2's {@code Expr}
 * — SSA values need an execution environment because phi selection depends
 * on which predecessor control actually arrived from). Used purely to
 * cross-check {@link SsaConstructor}'s output against real bytecode
 * execution, the same rigor Phase 2 used for straight-line kernels.
 */
public final class IrInterpreter {

    private static final int MAX_STEPS = 1_000_000; // guards against a construction bug producing a bad CFG loop

    private IrInterpreter() {}

    public static double run(IrFunction fn, double[] paramValues) {
        Map<IrValue, Double> env = new HashMap<>();
        for (IrValue.ParamValue p : fn.params) {
            env.put(p, paramValues[p.paramIndex]);
        }

        BasicBlock current = fn.entry;
        BasicBlock prev = null;
        int steps = 0;

        while (true) {
            if (++steps > MAX_STEPS) {
                throw new IllegalStateException("IrInterpreter exceeded " + MAX_STEPS + " steps — likely an infinite loop bug");
            }
            for (IrValue v : current.owned) {
                if (v instanceof IrValue.PhiValue phi) {
                    if (phi.replacement != null) continue;
                    IrValue incoming = phi.operands.get(prev);
                    if (incoming == null) {
                        throw new IllegalStateException("Phi at " + current + " has no operand for predecessor " + prev);
                    }
                    env.put(v, env.get(incoming.resolve()));
                } else if (v instanceof IrValue.ConstValue c) {
                    env.put(v, c.value);
                } else if (v instanceof IrValue.BinOpValue bin) {
                    double l = env.get(bin.left.resolve());
                    double r = env.get(bin.right.resolve());
                    env.put(v, apply(bin.op, l, r));
                }
            }

            Terminator term = current.terminator;
            if (term instanceof Terminator.Return ret) {
                return env.get(ret.value().resolve());
            } else if (term instanceof Terminator.Jump jump) {
                prev = current;
                current = jump.target();
            } else if (term instanceof Terminator.CondBranch cb) {
                double l = env.get(cb.left().resolve());
                double r = env.get(cb.right().resolve());
                boolean taken = compare(cb.cmpOp(), l, r);
                prev = current;
                current = taken ? cb.trueTarget() : cb.falseTarget();
            } else {
                throw new IllegalStateException("Unknown terminator type: " + term);
            }
        }
    }

    private static double apply(String op, double l, double r) {
        return switch (op) {
            case "+" -> l + r;
            case "-" -> l - r;
            case "*" -> l * r;
            case "/" -> l / r;
            default -> throw new IllegalStateException("Unknown op: " + op);
        };
    }

    private static boolean compare(String cmpOp, double l, double r) {
        return switch (cmpOp) {
            case "<" -> l < r;
            case "<=" -> l <= r;
            case ">" -> l > r;
            case ">=" -> l >= r;
            case "==" -> l == r;
            case "!=" -> l != r;
            default -> throw new IllegalStateException("Unknown cmpOp: " + cmpOp);
        };
    }
}
