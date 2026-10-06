# Kotlin constructor/function source-name collisions

Java can declare a nested class `A` with constructor `A()` beside an instance method
`A()`. These are distinct JVM members. Kotlin 2.4.0, pinned by this project, reports
`CONFLICTING_OVERLOADS` for the corresponding source declarations.

For a proven unique pair, the Kotlin backend keeps both encoded names and emits
`@Suppress("CONFLICTING_OVERLOADS")` on that method and secondary constructor only.
Construction uses a collision-safe imported class alias; virtual and super calls
keep their receiver and method name. No `@JvmName`, virtual-family rename, class
rename or descriptor change is introduced. This is a tested source representation,
not an accuracy-gate exemption or blanket suppression of generated diagnostics.
K2 additionally warns that suppressing this error relies on unspecified compiler
behavior that will not be preserved. That warning remains visible in the compiler
report: the selected original fixture compiles **with warnings**, not cleanly.
The runtime proof is specific to the pinned compiler; it is not a portability
promise for future Kotlin compilers.

The first supported scope is a public, zero-argument instance method beside a
public, static, ordinary nested class's public zero-argument constructor. The
owner must have the known `Object` superclass and no interfaces. Empty parameter
lists avoid guessing equivalence across Kotlin nullable, vararg, default or generic
parameter projections. Duplicate emitted names, static methods, nonpublic/inner
nested classes, effective user renames and unknown owner hierarchies receive an explicit diagnostic when
such a zero-argument collision is found. Parameterized collisions and other naming
forms remain outside this proof; compiler failures are not hidden.

The per-output plan has a bounded aggregate work budget and discovers referenced
collision classes lazily. Constructor aliases participate in the existing two-pass
import allocator and reserve member, local, parameter and imported names. Each
constructor call also requires a complete loaded member hierarchy for its caller
and every lexical enclosing class. An unknown superclass/interface could expose
an inherited callable with exactly the imported alias: Kotlin can silently bind
`JvmA()` to that value's `invoke()` instead of the constructor. Unproved calls
receive a method diagnostic while healthy siblings remain visible. The shared
scope proof bounds aggregate traversal, descriptor lookup work and retained
results; exhausting its limits cannot grow a failure cache indefinitely.

Explicit
user renames still follow the existing alias contract, but a renamed collision
pair does not receive this exact-JVM-identity workaround. In particular, the
existing renderer does not consistently propagate an outer rename to all nested
type references; this slice diagnoses such pairs rather than claiming to fix
that separate naming gap.

Tests execute the unchanged pinned `TestWrongCode2`, including its recursive
`A()` method, and distinguish construction from calls through loaded and external
subclasses. Precompiled Java callers check exact descriptors, virtual and super
dispatch, null receivers/returns and effects. A separately compiled Kotlin subclass
checks the pinned compiler's declaration-level suppression behavior. An external
`Function0<A>` field provides a negative compiler/runtime control for alias capture;
loaded ancestor metadata proves the corresponding generated constructor remains
correct, while missing ancestor metadata produces the required diagnostic. Compiler
upgrades must rerun these checks. Passing them does not establish support for all
Kotlin name collisions or all external Kotlin reflection/metadata consumers.

A synthetic subclass initially exposed a separate existing limitation: an inherited
static field encoded as `Child.trace` is emitted through `Child`, although Kotlin
companion fields are not inherited. The dispatch fixture explicitly qualifies its
own trace field to isolate this work; inherited static-field resolution is not
claimed fixed here.
