package io.hyperion.core.reflection.ast;

import java.lang.classfile.TypeKind;
import java.util.List;

/**
 * The result of extracting one {@code @GpuKernel}-annotated method's body
 * into an {@link Expr} tree: the method's name, its parameters (in
 * declaration order, matching {@link Expr.Param#paramIndex()}), the
 * return's {@link TypeKind}, and the reconstructed expression itself.
 */
public record KernelAst(
        String methodName,
        List<ParamInfo> parameters,
        TypeKind returnKind,
        Expr body
) {
    public record ParamInfo(String name, TypeKind kind) {}

    /** Pretty-prints as {@code kernelName(a, b, c) = (a * b + c)}. */
    @Override
    public String toString() {
        String params = String.join(", ", parameters.stream().map(ParamInfo::name).toList());
        return methodName + "(" + params + ") = " + body;
    }
}
