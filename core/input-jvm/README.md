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
