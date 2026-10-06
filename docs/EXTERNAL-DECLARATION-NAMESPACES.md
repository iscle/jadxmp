# External declaration namespace foundation

This is a declaration-reading prerequisite, not a backend classpath integration or a fix for missing dependencies. No emitted sources, oracle profiles, or corpus classifications use the new index yet.

`ClassDeclarationData.memberTypes` preserves declared member type identities, source names and access flags. `null` means the provider did not supply this capability; an empty list positively describes no declared member types. JVM declaration parsing supplies the complete current-owner `InnerClasses` inventory. It keeps literal dollar identifiers distinct from nesting and does not mistake a referenced class's members or local classes for members of the current class. Matching constant-pool aliases are deduplicated; contradictory metadata fails explicitly. Text work is bounded before canonical-name hash/comparison work.

The class-file inventory requirement comes from [JVMS 4.7.6](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.7.6): declared member classes/interfaces must be represented even when otherwise unused. This does not prove the completeness of an entire supplied classpath. Missing ancestors remain missing.

`NamespaceDeclarationModel` builds a separate `NamespaceDeclarationIndex` without executable `IrClass` nodes or generic-signature validation. Class flags and own member access flags remain distinct. Hierarchy, own nesting, and member inventory each carry an available, unknown, or invalid result. Thus a malformed generic signature cannot discard otherwise valid namespace facts; malformed hierarchy or cyclic enclosure cannot masquerade as a complete empty scope. Conflicting supplied owner/member facts invalidate only the affected capabilities. Duplicate class identities and work-budget exhaustion fail the whole build instead of choosing an arbitrary declaration or publishing partial completeness.

Records snapshot input lists. Build operations and lookup calls charge text before hashing, including repeated lookups on JavaScript/Wasm. Traversals are iterative, bounded, and stop at missing external vertices. Lookup requires a caller-owned budget shared across the complete proof; a `Known` record is not proof that its ancestry is complete. No failed-lookup cache grows after exhaustion.

The existing nesting contract does not distinguish a named local class in an initializer from a member merely by `Nested(owner, innerName, enclosingMethod = null)`. Consumers must use the positive member inventory to prove a canonical member name; they must not infer that relation from dollar characters or this nullable method shape. Unknown legacy own nesting remains unknown, distinct from validated top-level metadata.

Next steps require independent review before implementation:

- Expose explicit caller-supplied declaration input through the facade, with clear lifecycle and invalidation; keep archive/file IO outside common engine code.
- Resolve a source scope from loaded program declarations first, then external declaration facts. Preserve unknown/cyclic/malformed/budget outcomes and inspect all relevant lexical owners and ancestors.
- Feed that proof into Java exact-name and Kotlin import-alias planning without importing library declarations into generated program output.
- Test real supplied classpaths containing harmless and shadowing inherited member types, plus missing/partial declarations and long hostile names. Keep generic arity and namespace profiles distinguishable in measurements.

`variables/TestVariables6.smali` still lacks its proprietary superclass/interface declarations. Its original pinned test disables compilation and supplies no such dependencies. This foundation does not invent them, waive its diagnostics, or establish annotation source binding for that fixture.
