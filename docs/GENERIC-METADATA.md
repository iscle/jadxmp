# Generic declaration metadata foundation

Classpath declaration loading is separate from executable program input. `JvmInput.readDeclaration(bytes)`
reads class/member identities, flags, nesting and raw Signature attributes into format-neutral input
DTOs. It never reads Code attributes or creates code readers. `ExternalDeclarationModel.build` validates
these declarations with the common parser/model and returns a typed `GenericDeclarationIndex` plus explicit
diagnostics. Duplicate declaration identities are rejected. No reflection, filesystem discovery or JVM
APIs are used by the engine; callers supply dependency bytes.

Erased descriptors and hierarchy remain authoritative. Optional generic types are separate IR attributes;
class and method formals bind lexically, including shadowing and frozen bound erasure. Parsing is bounded
by text length, nesting, source nodes and erasure work. Aggregate declaration work and retained scope storage
are bounded too. Empty nested scopes share immutable collections. Limits produce unsupported errors,
not malformed-metadata recoveries; cancellation and provider bugs are not downgraded.

Proven malformed/descriptor-inconsistent optional metadata produces RECOVERY diagnostics and retains usable
erased declarations. Unknown scope, unsupported valid metadata, captured enclosing-method formals,
parameterized owner syntax and unproved omitted constructor parameters produce ERROR diagnostics. Classes
with errors are excluded from the validated external index. Nonstatic nested classes requiring generic
outer scope are explicitly unsupported in the current flat catalog; static siblings remain usable.

`ClassData.reflectiveNesting` distinguishes independently known reflective enclosure from best-effort source
nesting. Unknown third-party input remains unknown. DEX providers retain existing source nesting behavior,
while separately exposing absence of all system enclosing/inner annotations as reflective top-level scope.
Explicit complete metadata preserves its owner; partial, malformed, duplicate or non-system forms remain
unknown. Undefined-variable recovery is permitted only with independently complete reflective scope;
static source restrictions and missing enclosing owners are not treated as corrupt metadata.

This foundation does **not** attach signatures to executable `ModelBuilder.build()` output and does not
change source rendering. Native executable JVM input still rejects Signature attributes as before.
Declaration-only builds use the same model implementation with code reading disabled and signature
attachment enabled. Future executable/source integration must pass its own semantic and differential
gates; the current open-class bridge blocker is documented in [GENERIC-SOURCE-LIMITS.md](GENERIC-SOURCE-LIMITS.md).

Validation includes all-target parser/model/provider tests, malformed/deep/wide metadata stress, code-reader
isolation, and actual javac class-file ingestion. The native catalog test checks generic bounds, erased
identities, method shadowing, arrays, external nested-scope rejection and original null/identity execution.
These prove the metadata foundation, not complete generic source reconstruction or dependency discovery.

## Explicit Kotlin classpath profile

Kotlin raw reference types can consult a supplied validated catalog for their generic arity, rendering
unknown parameters as stars (for example `AbstractFloatAssert<*>`). This applies to type positions only;
constructor names, static owners and class literals remain bare names. Generated program definitions take
precedence and remain erased while executable generic reconstruction is disabled. Stars do not invent
compatible arguments for generic setters or infer a source contract; unsupported calls can still fail to
compile. External declarations never become generated program classes.

The expanded upstream runner defaults to `metadata_profile=none`, preserving its existing measurement
configuration. An opt-in profile uses the exact AssertJ JAR already on the original/rebuilt compilation
classpath:

```bash
./gradlew :tools:oracle:upstreamJavaRoundTrips \
  -Djadxmp.upstream.metadata=fixture-classpath-v1
```

The report records the profile consumer (candidate Kotlin only), artifact name/SHA-256/byte count,
class admission/rejection diagnostics and skipped versioned/module entries. Reference Java and candidate
Java remain unchanged. Reports using different profiles are separate measurements; profile-assisted gains
must not be presented as improvements to the metadata-free baseline.

The JVM-only tool hashes the same bounded archive bytes it parses and uses declaration readers only.
Limits are 16 explicit JARs, 20,000 archive entries, 4 MiB per expanded entry (including skipped resources),
128 MiB per archive and total expanded data, 200,000 members and 16 million aggregate signature characters.
No dependency discovery, reflection-based type reconstruction or fixture-specific declaration stubs are
used. Unsupported metadata remains visible in the report and absent from the validated index.
