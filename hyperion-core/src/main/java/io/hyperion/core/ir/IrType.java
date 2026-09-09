package io.hyperion.core.ir;

import java.lang.classfile.TypeKind;

public enum IrType {
    I32, I64, F32, F64;

    public static IrType from(TypeKind k) {
        return switch (k) {
            case INT, SHORT, BYTE, BOOLEAN, CHAR -> I32;
            case LONG -> I64;
            case FLOAT -> F32;
            case DOUBLE -> F64;
            default -> throw new IllegalArgumentException("Unsupported IR type kind: " + k);
        };
    }
}
