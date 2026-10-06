# Native JVM input: lowering proposal

The current `core:input-jvm` foundation parses class-file envelopes, constant pools, bounded Code
attributes and raw JVM instructions. A separate frame-primitives batch adds typed stack/local
storage and permutations, snapshots, and constructor alias transitions; it passed independent
review. The primitive normalizer produces the shared CodeReader model; its straight-line slice has
passed independent review. A bounded primitive control-flow extension has also passed independent
review and JVM/JS/Wasm tests. The module is not registered with `core:api`; general native JVM decompilation is
incomplete. Reference/exception frame analysis, general register lowering, and remaining
input-contract changes below are **proposed, not implemented**.
The fused-result and explicit inline-switch prerequisites have landed separately.

The shared input contract now carries format-neutral `ClassNesting` and member modifiers. DEX
extracts its annotation representation at its parser boundary; compatibility fallbacks remain for
older plugins. Explicit top-level metadata suppresses dollar-name nesting, while an explicit missing
owner never falls back to a different name-derived owner. JVM declaration metadata decodes
`SourceFile`, the current class's `InnerClasses` entry and `EnclosingMethod` (including initializer
contexts with method index zero). `NestHost`/`NestMembers` are not lexical enclosure. Names and
enclosing-method references are retained for later reconstruction; emitting source names and updating
all references together remains separate work. A class-only adapter and opt-in facade integration
are implemented and independently reviewed; archive loading/default registration remain pending.

The engine remains clean-room and multiplatform. Format decisions follow the
[JVMS class-file specification](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)
and [instruction specification](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-6.html).
The behavioral oracle remains the original pinned jadx commit recorded in
[`tools/jadx-reference/baseline.properties`](../tools/jadx-reference/baseline.properties).
No upstream implementation is an engine dependency.

## Proposed stages and boundaries

Keep the lowering stages in `core:input-jvm`, depending on `core:input` and `core:binary-io` only.
They must not depend on `core:pipeline` or `core:ir`, and must not implement a second SSA engine.

1. `JvmBytecodeDecoder` recovers raw instructions and validates direct branch boundaries.
2. `JvmFrameAnalyzer` computes incoming abstract locals and operand stacks with a worklist over
   normal and exceptional control flow. Constant-pool operand semantics and instruction transfer
   functions are validated here or in a dedicated preceding validator.
3. `JvmRegisterLowering` emits the shared register-based input vocabulary, explicit copies,
   ordered exception regions, and source-position mappings.
4. The existing method decoder, CFG, SSA, inference, structuring, and emitters consume that input.

Keep each method lazy and fault-isolated. Unsupported instructions or invalid frames must produce a
method diagnostic, not guessed stack contents or silently omitted instructions. StackMapTable
parsing and compatibility checks are a separate necessary layer; unchecked metadata is not proof
that a method is valid. Legacy `jsr`/`ret` requires explicit return-address/subroutine normalization;
initial lowering should report it as unsupported rather than inventing a successor.

The implemented primitive normalizer validates eagerly before returning its CodeReader, so reading
its register count cannot trigger a deferred lowering failure. It supports primitive constants,
loads/stores, iinc, stack permutations, arithmetic, conversions, comparisons and returns. Incoming
parameters occupy the high-register bank; raw bytecode positions remain in instruction file offsets,
while normalized instruction offsets are unique ordinals. Integer branches, goto/goto_w, primitive
joins/loops and both switch forms are implemented. Handlers, calls, reference operations,
constructors, jsr/ret and unreachable bytecode regions produce explicit unsupported-method diagnostics.

`JvmPrimitiveFlow` discovers basic blocks and computes their incoming frames with a worklist.
The same primitive transfer functions validate analysis and emission; analysis emits nothing,
and each block is emitted once after convergence. Logical stack types must agree exactly at joins;
incompatible locals become unusable, including overlapping wide slots. Incoming parameter copies
run once, while back edges to raw PC zero enter after that prologue. Original NOP/pop targets map
to the next emitted instruction. Inline switches retain explicit case and default destinations.
Dense retained frame storage is limited to 4,000,000 cells per method, including initial/working
frame capacity; scan/transfer work is limited to 10,000,000 units. Exhaustion produces a method
diagnostic. These limits avoid unbounded max_locals-by-block storage and repeated scans; snapshots
are made at block boundaries, never for each instruction. StackMapTable validation remains pending.

Reuse checked type/frame values by constant-pool entry or descriptor within each method/class,
including future constructor initialization transitions. Repeated worklist visits must not rescan
long shared descriptors. Do not use an unbounded global cache. The primitive slice parses method
descriptors once and caches numeric constants/reference frame types; extend the parser's hostile
shared-string tests to the whole-method analyzer when that stage lands.

## Class-only adapter and facade boundary

`JvmInput.loadClass(name, bytes)` parses one class into the existing CodeLoader/ClassData model.
It maps native descriptors, lexical metadata, member modifiers and typed ConstantValue initializers
without manufacturing Dalvik metadata. Method descriptors are cached within the class; a declaration
work budget of ten million referenced characters bounds repeated string hashing/materialization.
Each body provider validates Code multiplicity/abstract/native constraints and caches its reader or
original ordinary failure. Cancellation is rethrown without poisoning that cache. `loadClass` itself
does not force bodies; the facade's guarded ModelBuilder body access converts failures into method
errors while retaining successful sibling methods.

Unsupported semantic declaration attributes currently fail explicitly: annotations, generic
signatures, declared throws, annotation defaults, parameter metadata, record/sealed metadata and
nest-access metadata. Method-level rejection stays per-method; field/class rejection stops this
single-class load. The adapter preserves JVM synchronization/field modifiers for both backends.
Kotlin emits instance monitor annotations and explicit static bodies locking the original declaring
Class object, plus backing-field volatile/transient annotations. Targeted compilation/execution checks
cover class/receiver lock identity, exceptional release, collision-safe nested/renamed owners and
serialization. Compiler-generated enum members with unrepresentable modifiers are diagnosed;
this does not establish arbitrary modifier or JVM ABI parity. Unknown attributes remain ignorable and retain raw bytes. Executable BootstrapMethods
metadata remains deferred with unsupported dynamic-call bodies. This is not a complete class verifier.
The first end-to-end execution fixture uses an interface with static primitive methods to avoid
pretending constructor lowering is already supported. It passes real javac bytes through a test input
plugin, the facade/pipeline and both emitters, then recompiles and executes the generated sources.
No D8 conversion or upstream engine implementation enters this native path.

## Frame representation

Use logical operand-stack values with an explicit word width. Distinguish integer, float, long,
double, null, reference, uninitialized receiver, and an uninitialized allocation identified by its
original `new` bytecode offset. Local storage must additionally represent unavailable slots and the
second word of a category-two value.

The future analyzer must reuse validated reference/type values by constant-pool index or through a
per-method intern table. Reconstructing a reference value for every use would repeatedly rescan a
shared long descriptor and undo the parser's work-amplification protection. Keep this cache scoped
to the input/analysis operation, not mutable global state.

Count `max_stack` in words, not values. Check every local access against `max_locals`; replacing one
word of an overlapping long/double must invalidate the old two-word value. Stack merges must agree
on height and legal category shape; local merges must not invent values for unavailable slots.
Uninitialized allocation identity must survive copies and joins without becoming an ordinary
reference. Reference hierarchy resolution and StackMapTable checks need explicit contracts before
calling this a complete bytecode verifier.

For an ordinary exception edge, retain the pre-instruction local state and replace the operand stack
with one exception value. Keep handlers in table order. Constructors require a distinct exceptional
frame: successful invocation initializes all aliases of its allocation token, while exceptional
completion must make constructor-target aliases in locals unusable. It must not leave aliases that
a catch handler could use to retry initialization. Preserve the special receiver-initialization
flags as well. The exact version-dependent verifier representation belongs in the dedicated frame
validator; see the [JVMS constructor exception-frame rules](https://docs.oracle.com/javase/specs/jvms/se23/html/jvms-4.html#jvms-4.10.1.9.invokespecial).

## Register layout and alias preservation

Use a deterministic register bank for JVM locals and a separate bank for operand-stack word
positions. Loads copy a local into a stack register; they must not merely refer to the mutable local.
For example, `iload_0; iinc 0, 1; ireturn` returns the old value. Aliasing its stack entry to local zero
would return the new value.

Preserve the shared pipeline's incoming-parameter convention: receiver and parameter words occupy
consecutive registers at the **end** of the normalized frame, with long/double consuming two words.
Copy incoming parameters once into a contiguous bank of original JVM locals. Do not split that
local bank at the incoming-argument boundary: a legal long/double store may span an old parameter
slot and a nonparameter slot. The implemented layout is:

- all original JVM local words, including mutable copies of parameter words;
- operand-stack words;
- bounded scratch words for parallel copies;
- incoming receiver and parameter words.

The frame size is fixed before emission. `MethodParams.of` can then seed parameters correctly,
including instance receivers and mixed wide/narrow signatures. Entry copies preserve object-ness
and wide widths. Future raw-PC branch targets must map after this one-time prologue, including
back edges to original PC zero; they must never reinitialize mutable locals from incoming values.

Implement stack permutations over logical values. All legal `dup`, `dup_x1`, `dup_x2`, `dup2`,
`dup2_x1`, and `dup2_x2` category forms need coverage. Emit parallel copies using scratch when
required; a sequential swap can overwrite a still-needed source. Duplication copies a value, never
re-executes its producer. The same rule preserves reference identity and side-effect order.

Keep allocation and constructor invocation distinct in normalized input. The current constructor
reconstruction pass traces MOVE aliases back to NEW_INSTANCE; test that path before relying on it
for JVM allocation patterns, including constructor failure and allocation ordering.

## Shared input-contract gaps found in the audit

These changes should land as separately tested prerequisites, not as hidden JVM-specific branches
inside later pipeline passes.

| Contract | Current behavior | Proposed correction |
| --- | --- | --- |
| Fused results | Implemented: `MethodDecoder` honors `Instruction.resultRegister` for calls/custom calls and filled arrays. | Retains declared result types and DEX move-result behavior; invalid void/out-of-frame results carry diagnostics. |
| Call arguments | The decoder advances argument indexes by descriptor word width. | Document and expose word-indexed arguments, including the second entry for long/double. |
| Switches | Implemented: `Opcode.SWITCH` carries an `InlineSwitchPayload` with explicit absolute case/default positions. The legacy DEX payload path remains supported. | No synthetic payload or default jump is needed; unresolved inline destinations produce visible diagnostics. |
| Instruction positions | SPI comments assume 16-bit code units. One JVM instruction may lower into several register instructions. | Give emitted instructions unique normalized positions and retain original bytecode/file offsets separately. |
| Targeted NOPs | MethodDecoder drops NOPs; CfgBuilder resolves targets by exact remaining offsets. | Preserve targeted normalized NOPs or explicitly map raw NOP PCs to the next real emitted instruction. Never rely on implicit gap redirection. |
| Exception endpoints | Shared `TryBlock` ends are inclusive; JVM Code ranges are half-open. | Map original boundaries deliberately through emitted positions, including the last expansion of the last protected instruction. |
| Handler order | `CatchHandler` separates typed entries and appends catch-all last. | Represent ordered clauses without regrouping overlapping JVM regions or moving catch-all entries. Preserve DEX compatibility. |
| Handler entry | JVM enters with an exception on the stack; normalized input uses MOVE_EXCEPTION. | Create an exceptional-entry adapter if the same raw handler PC is also normally reachable. A normal edge must retain its supplied stack value. |
| Debug positions | Lines/local ranges refer to original code offsets and local slots. | Apply the same source-position and register maps used for branches and exceptions. |

Position mapping needs at least original-PC-to-first-emitted-position and end-boundary mappings.
Branches target the first instruction in an expansion; exception and debug ranges cover the correct
whole expansions. Original file offsets must remain useful for diagnostics/disassembly. Define these
contracts before choosing an encoding so synthetic copies do not share ambiguous branch targets.

## Proposed test slices

Each slice starts with failing common tests, runs JVM/JS/Wasm checks, and receives independent
adversarial review. Once executable output is available, compare runtime values, side effects,
exception types and identity, not only compiler acceptance.

1. **Frame primitives:** all twelve legal dup-family forms; illegal category permutations; pop,
   pop2 and swap; stack underflow/overflow; overlapping wide local invalidation; uninitialized-token
   copying and initialization; failed constructor-target aliases becoming unusable; rejection of
   retrying a failed constructor or reusing its failed target, including receiver-initialization flags.
2. **Straight-line primitive lowering:** constants, loads/stores, iinc, arithmetic, conversions and
   returns; mixed signatures such as `(IJI)J`; instance receiver mapping; load-before-iinc;
   wide values surviving local overwrite; NaN and negative-zero bit patterns.
3. **Normal control flow:** stack-carrying diamonds, loops, back edges, incompatible joins,
   dense/sparse switches with non-fallthrough defaults, targets landing on raw NOPs, and
   original-to-normalized offset mappings, including NOPs at protected-range boundaries.
4. **References and calls:** field evaluation order, wide call arguments and return values,
   checkcast failure, arrays, repeated references, constructor aliases, constructor delegation,
   failed constructors, and allocation/call ordering. Keep dynamic call-site/bootstrap work explicit.
5. **Exceptions:** division and arraylength failures after earlier local assignments; throwing
   result writes that must not commit; nested and overlapping handlers; catch-all ordering;
   normal entry into a handler PC; constructors inside protected ranges. Preserve caught-object
   identity and verify handler-visible prethrow state.
6. **Integration:** method/class metadata, StackMapTable validation, archive loading, facade
   registration, native JVM corpus execution through both emitters, and the pinned differential gate.

The frame-primitives slice is implemented and independently reviewed; straight-line primitive
lowering is implemented and independently reviewed. Primitive normal-flow analysis and lowering
are implemented and independently reviewed. Reference/exception analysis and general lowering
remain future work. Full native JVM support remains incomplete until the later stages and their
runtime checks pass.
