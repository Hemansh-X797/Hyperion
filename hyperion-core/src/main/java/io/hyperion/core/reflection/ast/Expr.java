package io.hyperion.core.reflection.ast;

import java.lang.classfile.TypeKind;

/**
 * A minimal expression AST reconstructed from JVM bytecode by symbolically
 * executing the method's operand stack (see AstExtractor). This covers the
 * "straight-line arithmetic kernel" shape: some parameters and constants
 * combined by binary arithmetic operators into a single returned value —
 * exactly the shape of elementwise/FMA-style GPU kernels before Phase 3
 * adds control flow (branches/loops) via CFG+SSA lowering.
 */
public sealed interface Expr permits Expr.Param, Expr.Const, Expr.BinaryOp {

    TypeKind kind();

    /** Evaluates this expression tree given the kernel's actual parameter values, for correctness testing. */
    double evaluate(double[] paramValues);

    /** A reference to one of the kernel method's parameters, by declaration order (not raw JVM slot index). */
    record Param(int paramIndex, String name, TypeKind kind) implements Expr {
        @Override
        public double evaluate(double[] paramValues) {
            return paramValues[paramIndex];
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A compile-time constant pushed onto the stack (ldc, iconst_*, fconst_*, etc.). */
    record Const(double value, TypeKind kind) implements Expr {
        @Override
        public double evaluate(double[] paramValues) {
            return value;
        }

        @Override
        public String toString() {
            // Print integral constants without a trailing ".0" for readability.
            return (value == Math.rint(value) && !Double.isInfinite(value))
                    ? Long.toString((long) value)
                    : Double.toString(value);
        }
    }

    /** A binary arithmetic operation: one of + - * / (the four Vulkan/SPIR-V-representable scalar ops we support so far). */
    record BinaryOp(String symbol, Expr left, Expr right, TypeKind kind) implements Expr {
        @Override
        public double evaluate(double[] paramValues) {
            double l = left.evaluate(paramValues);
            double r = right.evaluate(paramValues);
            return switch (symbol) {
                case "+" -> l + r;
                case "-" -> l - r;
                case "*" -> l * r;
                case "/" -> l / r;
                default -> throw new IllegalStateException("Unknown operator: " + symbol);
            };
        }

        @Override
        public String toString() {
            return "(" + left + " " + symbol + " " + right + ")";
        }
    }
}
