# Kotlin enum constructor reconstruction

Kotlin enum entries with user arguments now have one class-level construction plan. The plan is
shared by import discovery and rendering, entry argument emission, secondary-constructor headers,
and final-field initialization checks. It does not rewrite the executable IR.

For example, a JVM constructor `(String name, int ordinal, long value, String text)` is emitted as
`private constructor(value: Long, text: String?)`. Kotlin regenerates the same synthetic JVM prefix.
A source `this(...)` chain preserves the exact remaining overload, nullable reference identity,
argument evaluation order, and body effects. Entries follow the proven initialization/ordinal order,
not the input field table's lexical ordering.

The proof requires actual uninitialized-this SSA identity, unchanged name/ordinal forwarding,
private constructor descriptors, an acyclic delegation graph, straight-line regions, and complete
producer trees whose evaluation order matches the original statements. A consumed producer may not
remain live elsewhere. Hidden name/ordinal parameters cannot feed explicit user arguments or survive through coalesced
parameter variables. The exact, single-use entry-name literal is compiler-regenerated metadata; other
string producers retain their resolution order and multiplicity, and unused prefix strings are not
silently discarded. Canonical `values`, `valueOf`, and values-array construction are checked before
the compiler replaces them. The backing array may use either exact entry-field reads or the exact SSA results of the already
constructed and stored entries, in the same slot order. Such register reads are terminal values in
the array proof: constructors and their effects are never inlined or repeated. Reordered, duplicate,
unknown, wrapped-construction, and indirect-copy array elements are rejected; remaining uses of any
suppressed value still reject reconstruction. Filled-array and empty-array helper forms are covered; other array
initialization forms remain unsupported. Arbitrary anonymous entry subclasses, constructor control
flow outside this proof, missing or inconsistent descriptors, and unsafe relocation remain explicit
reconstruction failures. Hidden backing-array/helper references and extra writes to entry fields are
rejected across the fully lowered output unit, including its nested classes. Other output units may
be undecoded or concurrently lowered, so their mutable bodies are never used as proof. Previously stored entries may be read by later entry arguments;
forward/current entry reads and constructor-time entry reads need diagnostics. `entries`, `name`, and
`ordinal` entry spellings are rejected without renaming their observable enum identities. Synchronized
helpers cannot be silently regenerated without their monitor contract. A failed entry expression rolls
back its partial source and leaves a diagnostic alongside healthy class members.

The descriptor-only `EnumConstructorParameters` helper never normalizes annotation positions.
In particular, it must not pad the short enum parameter-annotation lists observed after D8 conversion.
Annotation attachment and its source representability are separate contracts.

The analysis charges up to one million units of node/descriptor-text work per output, caps expression
and array-type depth at 32, and caps constructor count at 256 before structural signature lookup.
Failures leave the original bodies available with an error marker; budget exhaustion does not create
an unbounded cache of failed plans.

Runtime tests compile genuine Java fixtures, run the original, convert them through the pinned D8,
and compare the original pinned jadx and candidate Kotlin output. They cover constructor descriptors,
name/ordinal order, wide values with significant high bits, null and reference identity, this-chains,
empty enums, `values()` clone identity, `valueOf`, and throwing arguments which must prevent later
arguments and constructor bodies from running. No source-output string comparison substitutes for
these execution checks. Fresh facade instances also compile and execute the same multi-output input
in forward/reverse lazy order and repeated parallel runs, while a common control proves unrelated
body population cannot change the enum plan. Additional verifier-valid JVM fixtures are patched, executed, and then
converted through D8 to test hidden-ordinal delegation, synchronized `values()`, backing-array
identity/mutation, entry reassignment, and early entry reads. These are explicit candidate diagnostic
controls, not parity passes. The pinned reference cannot compile the hidden-ordinal reconstruction
and loses the synchronized flag on `values()`; those measured limitations stay asserted separately.

An enum factory in its own companion is a measured unsupported boundary: original Java and pinned
jadx execute it, but Kotlin rejects companion access while entries initialize. Such direct own-member
initialization dependencies receive a reconstruction diagnostic. Reentry through arbitrary external
factories and the wider companion-initialization model remain broader Kotlin limitations. The tests
do not establish all enum behavior, annotation parity, or full public-final-field JVM ABI parity.

A standalone raw-smali execution fixture retains constructor results across the entry stores into
`$VALUES`, including significant long bits and nullable text. Original Java, pinned reference Java,
and candidate Kotlin agree on entry identity, clone identity, descriptor shape, constructor/argument
trace, and failure identity when the second argument throws. Three fresh parallel facade runs also
match sequential output and execution. This extension does not change annotation projection.
