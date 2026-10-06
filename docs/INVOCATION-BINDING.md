# Source overload binding

Bytecode identifies an exact method descriptor, while Java and Kotlin source compilers choose
an overload from argument types. A narrowed expression can therefore select the wrong overload.
The pinned original `TestOverloadedMethodInvoke` reproduces this: an `Exception` expression passed
to the descriptor taking `Throwable` selected the overload taking `Exception` in both candidate
languages before this repair. The unchanged original and pinned reference checks pass.

`InvocationSourceBinding` in shared `core:codegen` computes argument positions that need an
explicit source type. It uses loaded declarations and proven reference widening, retains the
original method owner and invocation kind, and leaves SSA types unchanged. For example,
`select((Object) value)` retains the `Object` overload when `value` has source type `String`.
Kotlin uses the corresponding nullable boundary type. Nullable receiver paths evaluate receiver
and argument expressions once before applying the argument views and checking the receiver.

The analysis is local to one emitted output, shared across import discovery and final rendering,
and memoizes overload sets, conflicts and ancestry.
Its aggregate work budget charges graph traversal and string/type hashing even on cache hits.
Budget failures retain visible member diagnostics and healthy siblings, including enum argument
and inlined field-initializer contexts. Failed field hoists never suppress residual initialization.
Unsupported generic types, unknown inheritance, primitive/boxing overloads and varargs applicability
are outside this proof;
they do not authorize guessed runtime casts. Loaded platform declarations take precedence over
the small fixed exception ancestry used when those declarations are absent. Constructors consult
only their declaring class; ordinary calls also inspect loaded receiver ancestry.

## Runtime evidence and remaining linkage limitation

`InvokeOverloadBindingSemanticsTest` executes the unchanged upstream check in both languages.
Its additional source fixture compares original, pinned reference and candidate results, including
multiple conflicting arguments, nullable arrays, inherited virtual dispatch, overloaded constructors
and constructor delegation, null receivers, argument effects, and competing arithmetic failures.
These positive cases require strict equality with the original execution result and side-effect trace.

A separate probe recompiles only generated `Caller` against unchanged library classes:

```java
class Child extends Parent {}
// Target declares pick(Parent) returning 1 and pick(Child) returning 2.
static int call(Object value) {
    Child child = (Child) value;
    return Target.pick((Parent) child);
}
```

After compilation the probe deletes `Parent.class`, opens a fresh loader, and calls with null.
The original fails with `NoClassDefFoundError: linkage/Parent` during method resolution/verification.
On the same fixture, baseline `141ca2166dabe11b7fabd63606e0adca05111131` returned 2 in both
candidate languages. The pinned reference returns 1: it also loses the original linkage failure.
With source binding, candidate Java preserves the original failure. Candidate Kotlin returns 1:
it fixes overload choice but **still loses the pre-existing missing-dependency failure**.
Explicit nullable widening casts and typed temporaries in the tested Kotlin compiler produce
`checkcast Parent`; casting null does not force the missing superclass relation to resolve in
the way the original verifier does. Source widening casts cannot generally be assumed erased.

The Java test strictly requires the original failure. The separately named
`kotlinAndReferenceStillLoseMissingAncestorLinkageFailure` control asserts the exact original,
reference and Kotlin observations. It records an unresolved semantic limitation, **not parity**.
It grants no diagnostic exemption, changes no scoreboard policy, and does not weaken the positive
execution comparisons. General missing-runtime-dependency equivalence remains a readiness blocker.

Run the focused execution evidence with:

```sh
./gradlew :tools:oracle:test --tests '*InvokeOverloadBindingSemanticsTest'
```
