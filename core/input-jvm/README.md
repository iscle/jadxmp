# JVM input

This module is the multiplatform foundation for native `.class` and `.jar` input. It is not yet
registered with `core:api` by default. The class-only `JvmInput.loadClass(name, bytes)` entry produces
native input for an explicitly supplied facade plugin; archive loading and general JVM lowering
remain incomplete.

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
reserved instruction bytes, payload lengths and switch key ordering. The register normalizer separately checks constant-pool operand kinds, frame/stack types, local
bounds and supported instruction versions. A successful structural decode alone does not establish
valid bytecode or complete normalization.

Frame primitives model typed logical stack values, wide local slots, stack permutations, independent
snapshots, and constructor alias transitions. They validate local and stack bounds without depending
on the IR or pipeline. Primitive joins conservatively invalidate incompatible locals and require
matching stack shapes/types. Reference joins preserve nullability and conservatively widen distinct
initialized types to Object. Precise reference hierarchy/exception analysis and StackMapTable checks
are still pending; these primitives do not constitute a complete bytecode verifier. The proposed
stages and shared input-contract prerequisites are recorded in [docs/JVM-INPUT.md](../../docs/JVM-INPUT.md).

The register normalizer handles primitive/reference values with integer/reference/null branches,
loops and dense/sparse switches, eagerly producing the shared CodeReader model. It copies high-register parameters into contiguous JVM local slots and preserves
local-load snapshots, category-two values and floating-point constant bits. Bounded block-level
frame analysis precedes one-time emission; branch targets skip the parameter prologue and resolve
removed NOP/pop instructions. Switch default destinations remain explicit. Native reference/null
loads and stores (including wide local indexes) preserve snapshots and identity. Reference returns
accept exact/null/Object assignment; narrower assignment needs unavailable hierarchy information
and is diagnosed. Native checkcast/instanceof and String/Class ldc/ldc_w now use checked typed
constant-pool operands. A per-method ten-million-unit operand work budget charges resolution and
unique UTF8 lengths; aliases share validated descriptors/frame types. Wrong tags/categories and
class literals before class-file version 49 are diagnosed. Handlers, calls, allocations, dynamic/
method-type/method-handle constants, legacy subroutines and unreachable bytecode regions still
produce explicit method failures.
JVM tests compare normalized-register execution with real javac methods for branches, loops,
switches, overflow, mixed wide parameters, post-increment, signed zero and NaN; this is a bounded
normalization slice, not general JVM decompilation or complete bytecode verification.
Direct JVM class-byte tests also verify implicit byte/char/short return narrowing and boolean low-bit
masking, which must be explicit in normalized register instructions.

Declaration metadata decodes bounded `SourceFile`, `InnerClasses` and `EnclosingMethod` attributes
into the shared lexical nesting contract. It preserves member modifiers, local/anonymous owners and
enclosing methods; access-control nests do not imply lexical nesting. This metadata reader is not
a complete JVM metadata layer. The class adapter maps descriptors, nesting, modifiers and typed field
constants directly into ClassData. Concrete bodies use cached lazy providers; malformed or unsupported
bodies throw per-method diagnostics for the facade's existing fault-isolation guard. Cancellation is
never cached. Declaration processing has a ten-million-character work budget before repeated shared
strings enter maps. Unsupported semantic attributes (including annotations, signatures, throws,
record/sealed and nest-access metadata) are explicitly rejected at class/field scope or diagnosed at
method scope. Unknown attributes retain their bytes and remain ignorable as specified by the JVMS.
Module descriptors, contradictory class forms and duplicate declarations are rejected.

Native facade tests compile actual javac class bytes directly through both source emitters, recompile
the output and execute branch/loop/switch, numeric and type-operation edge cases without D8.
Constructors, calls and exception handlers remain unsupported, so this is partial native input. Reference execution tests compare original javac classes, the pinned jadx Java output
and candidate Java/Kotlin for nulls, identity, arrays and loops. Shared long descriptor comparisons
count against the analysis budget. Type-operation tests cover null and failed casts, exact array
types, integer consumers of instanceof, string interning and class-object identity. They verify
that type operations do not initialize their target and null casts/tests do not resolve a missing
array component class. Kotlin reference-array checks use the exact array Class after evaluating
the operand once and handling null; an erased `is Array<*>` check would be incorrect. The original
pinned jadx drops one unused throwing cast; that measured mismatch remains explicit in the test
while both candidate languages must retain the original ClassCastException.
The default input registry remains unchanged. JVM synchronization and field modifier bits remain
in the shared declarations. Kotlin emits instance synchronized annotations, static bodies locking the
original Class rather than Companion, and volatile/transient backing-field annotations. Dedicated
runtime tests check monitor identity/release and field reflection/serialization; unsupported synthetic
enum modifier contracts remain explicitly diagnosed. This is not general JVM ABI parity.

Field constants decode `ConstantValue` into typed input values, including narrowing, wide values,
strings and signed zero. Only static fields receive these initializers, whether final or not;
instance-field initialization belongs to constructor bytecode. Direct JVM execution checks cover
these distinctions. The class adapter exposes the decoded field values through `FieldData.constValue`.

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
