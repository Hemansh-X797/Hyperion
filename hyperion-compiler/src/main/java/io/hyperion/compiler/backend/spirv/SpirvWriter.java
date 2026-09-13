package io.hyperion.compiler.backend.spirv;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits a real SPIR-V binary module: the 5-word header plus the required
 * section ordering (capabilities, extension imports, memory model, entry
 * points, execution modes, debug names, decorations, types/constants/global
 * variables, then function bodies). Each instruction is encoded as the spec
 * requires: a single leading word packing {@code (wordCount << 16) | opcode},
 * followed by the operand words.
 */
public final class SpirvWriter {

    private static final int MAGIC_NUMBER = 0x07230203;
    private static final int VERSION_1_5 = 0x00010500;
    private static final int GENERATOR_MAGIC = 0xFFFF0001; // "unknown tool", vendor id 0xFFFF — honest: we're not a registered generator

    private int nextId = 1;

    public final List<Integer> capabilities = new ArrayList<>();
    public final List<Integer> extInstImports = new ArrayList<>();
    public final List<Integer> memoryModel = new ArrayList<>();
    public final List<Integer> entryPoints = new ArrayList<>();
    public final List<Integer> executionModes = new ArrayList<>();
    public final List<Integer> debugNames = new ArrayList<>();
    public final List<Integer> decorations = new ArrayList<>();
    public final List<Integer> typesConstsVars = new ArrayList<>();
    public final List<Integer> functions = new ArrayList<>();

    public int freshId() {
        return nextId++;
    }

    /** Emits one instruction with purely numeric operands into {@code section}. */
    public void emit(List<Integer> section, int opcode, int... words) {
        int wordCount = 1 + words.length;
        section.add((wordCount << 16) | (opcode & 0xFFFF));
        for (int w : words) section.add(w);
    }

    /**
     * Emits one instruction whose final operand is a literal UTF-8 string
     * (OpName, OpEntryPoint, OpMemberName, OpSource, ...): the string is
     * packed as consecutive little-endian words, 4 bytes each, NUL-terminated,
     * zero-padded to a full word — exactly the encoding {@code spirv-dis}
     * expects (verified: our output round-trips through it, see Phase4Demo).
     */
    public void emitWithString(List<Integer> section, int opcode, int[] leadingWords, String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        int stringWordCount = utf8.length / 4 + 1; // +1 for the guaranteed NUL terminator word-fragment
        int wordCount = 1 + leadingWords.length + stringWordCount;
        section.add((wordCount << 16) | (opcode & 0xFFFF));
        for (int w : leadingWords) section.add(w);

        int i = 0;
        while (i < utf8.length) {
            int w = 0;
            for (int b = 0; b < 4 && i < utf8.length; b++, i++) {
                w |= (utf8[i] & 0xFF) << (8 * b);
            }
            section.add(w);
        }
        // final terminating word: 0 if the last real word was full (no room for NUL byte 0
        // left inside it), otherwise the NUL byte(s) were already implicitly zero-padded above
        // since a Java int defaults to 0 in unset high bytes — but if utf8.length % 4 == 0 we
        // need one more all-zero word for the terminator to have its own slot.
        if (utf8.length % 4 == 0) {
            section.add(0);
        }
    }

    public int[] assemble() {
        List<Integer> all = new ArrayList<>();
        all.add(MAGIC_NUMBER);
        all.add(VERSION_1_5);
        all.add(GENERATOR_MAGIC);
        all.add(nextId); // bound = max id used + 1
        all.add(0); // schema, reserved, must be 0

        all.addAll(capabilities);
        all.addAll(extInstImports);
        all.addAll(memoryModel);
        all.addAll(entryPoints);
        all.addAll(executionModes);
        all.addAll(debugNames);
        all.addAll(decorations);
        all.addAll(typesConstsVars);
        all.addAll(functions);

        int[] words = new int[all.size()];
        for (int i = 0; i < words.length; i++) words[i] = all.get(i);
        return words;
    }

    /** SPIR-V's binary container is little-endian 32-bit words, per the spec. */
    public byte[] toBytes() {
        int[] words = assemble();
        ByteBuffer buf = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int w : words) buf.putInt(w);
        return buf.array();
    }
}
