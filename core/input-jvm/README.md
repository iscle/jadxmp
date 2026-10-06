# JVM input

This module is the multiplatform foundation for native `.class` and `.jar` input. It is not yet
registered with `core:api`: stack-to-register lowering, metadata normalization,
and archive integration must land before it provides decompilation.

The initial parser follows the [JVMS class-file format](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)
through Java 21 (major 65). Newer versions receive an explicit unsupported-version diagnostic.
It separates constant-pool references and descriptor syntax from the class/member/attribute
envelope. Unknown attributes retain their bytes and offsets for later bounded decoders; parsing
an envelope does not imply that bytecode or attribute semantics have been verified. Access-flag
combinations, duplicate declarations, superclass/module constraints and recognized attribute
legality remain to be validated before exposing this structure as normalized input. Repeated
constant-pool strings are validated once per syntax context to prevent work amplification.

The Code attribute decoder bounds bytecode to the JVM's 65,535-byte limit and retains ordered
exception handlers and nested attributes. The instruction decoder handles every defined opcode,
including `wide`, both switch forms and signed branches. It validates branch and handler boundaries,
reserved instruction bytes, payload lengths and switch key ordering. Constant-pool operand kinds,
frame/stack types, local bounds and version-specific instruction legality remain separate work;
structurally decoded instructions are not yet normalized decompiler input.

Frame primitives model typed logical stack values, wide local slots, stack permutations, independent
snapshots, and constructor alias transitions. They validate local and stack bounds without depending
on the IR or pipeline. A whole-method frame analyzer, type merges and StackMapTable checks are still
pending; these primitives do not constitute a bytecode verifier. The proposed
stages and shared input-contract prerequisites are recorded in [docs/JVM-INPUT.md](../../docs/JVM-INPUT.md).

The first register normalizer handles straight-line primitive methods, eagerly producing the shared
CodeReader model. It copies high-register parameters into contiguous JVM local slots and preserves
local-load snapshots, category-two values and floating-point constant bits. Unsupported control flow,
handlers, calls and reference operations produce explicit method failures. JVM tests compare normalized-register execution with real javac
methods for overflow, mixed wide parameters, post-increment, signed zero and NaN; this is a bounded
normalization slice, not general JVM decompilation or complete bytecode verification.
Direct JVM class-byte tests also verify implicit byte/char/short return narrowing and boolean low-bit
masking, which must be explicit in normalized register instructions.

Declaration metadata decodes bounded `SourceFile`, `InnerClasses` and `EnclosingMethod` attributes
into the shared lexical nesting contract. It preserves member modifiers, local/anonymous owners and
enclosing methods; access-control nests do not imply lexical nesting. This metadata reader is not
yet wired to a ClassData adapter or the facade. Annotations, signatures and the remaining attributes
still require semantic decoding; their raw bytes remain in the parsed class model.

All production code is `commonMain`; IO primitives come from `core:binary-io`. No ASM, D8 or
upstream jadx implementation enters the engine. The original jadx commit remains the behavioral
oracle in the separate JVM tooling modules.

Tests cover all constant-pool tags, forward references, two-slot holes, numeric bit patterns,
descriptor limits, truncation, hostile lengths and modern/historical version fields on JVM, JS
and Wasm. JVM tests additionally compile real records, lambdas, generics, protected methods, dense
and sparse switches, and modules using javac, then decode their Code payloads and instructions.

```sh
./gradlew :core:input-jvm:jvmTest :core:input-jvm:jsNodeTest :core:input-jvm:wasmJsNodeTest
```
