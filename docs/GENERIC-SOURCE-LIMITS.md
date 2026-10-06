# Pending generic source reconstruction

The generic metadata foundation is independent of source reconstruction. The broader implementation remains
pending because a valid open-class bridge cannot be specialized safely with the current source strategy.
No diagnostic severity or corpus gate policy has changed to admit it.

The pinned `corpus/smali/inline/TestOverrideBridgeMerge.smali` class is public and non-final, with a public
constructor. Its valid class Signature declares `Function<String, Integer>`, but its executable method is
`apply(Object): Object`: cast the argument to String, then invoke the separately named virtual `test(String)`.
There is no access or construction restriction establishing a closed world.

Emitting `Integer apply(String)` satisfies Java/Kotlin's generic interface contract. The source compiler
creates `apply(Object)` as a bridge that invokes the new virtual String overload. A previously compiled
subclass can already have an unrelated `apply(String)` method. Originally, an erased call on that subclass
executes the base Object method; after reconstruction, the compiler bridge dispatches to the subclass's
String method. Identity, return value and effects can all change. A final method alone does not prove safety:
it can create a new final overload that conflicts with an existing descendant method.

A second rejected workaround casts loaded receivers to their interface to reach the compiler-hidden erased
bridge. Fresh-loader probes demonstrated that changing an invokevirtual Base target to invokeinterface can
replace NoClassDefFoundError(Base) with NullPointerException when Base is absent, even after preserving
argument side effects. Kotlin's existing nullable receiver `!!` also has a separate missing-runtime-class
resolution-order limitation; this is not repaired by changing generic declarations.

Current concrete choices for future integration are:

1. Reconstruct only contracts with complete dispatch/call-site proofs, and keep unsupported cases visibly
   diagnosed with erased executable bodies. This is faithful for execution but currently introduces an
   enforced no-error regression for the pinned open bridge fixture.
2. Develop a source strategy that preserves the original symbolic owner, descriptors, dispatch and checks
   without creating a competing virtual overload. Such a strategy has not been demonstrated for the open
   fixture in either language.
3. Consider a separately reviewed policy for visible loss of valid reflective generic metadata while
   retaining erased execution. This would be a new policy decision, not the existing recovery rule for
   proven malformed optional metadata, and has not been adopted.

The original pinned jadx test expects the specialized String source method. That expectation is useful
comparison evidence but does not prove arbitrary descendant dispatch is preserved. Compiler acceptance
alone cannot resolve this blocker; original-versus-rebuilt execution, reflection and linkage controls are
required before the broader feature can land.
