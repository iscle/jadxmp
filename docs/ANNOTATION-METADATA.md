# Annotation metadata foundation

Input-to-IR annotation preservation is separate from source emission. The pipeline now snapshots
class, field, method and parameter annotations into IR-owned typed metadata. Parameter positions
exclude the receiver and count values rather than wide register slots. BUILD, RUNTIME and SYSTEM
visibility remain distinct; nested values have no visibility. No input-model objects enter IR.

`MethodData.annotationDefault` is the format-neutral default-value boundary. The DEX adapter
validates its class-level `AnnotationDefault` wrapper once when a method requests a default and
resolves defaults only to unique,
public abstract, zero-argument members of an annotation declaration. Wrong owners, duplicate
wrappers, unknown names and ambiguous members are failures, not missing defaults. Missing defaults
remain explicit `Ready(null)` values. A zero-method class never requests that lazy index: its
raw SYSTEM wrapper remains inspectable, including unknown element names, but whole-class default
validation is deferred. A real DEX-to-IR control records this boundary; it is not an invalid-input
policy exemption or a source-parity result.

Each annotation's value map is atomic. A validated type and visibility survive an unsupported value
(for example an `EnclosingMethod` METHOD reference or an anonymous `InnerClass` NULL name), with
the complete value map recorded as `Unavailable`. An invalid header instead makes the entire entry
`Unavailable` at its original list position. Valid annotations beside it survive. This lets consumers
route known metadata by typed identity without parsing diagnostic text or treating failed values as
an empty map. A failed nested annotation still invalidates its containing value/default atomically.
The existing nesting/signature consumers
continue reading their input metadata. No system namespace is silently filtered. Unsupported
NULL, FIELD, METHOD, METHOD_TYPE and METHOD_HANDLE annotation values retain diagnostic outcomes;
source representability of these values is not claimed.

Conversion has per-class limits of 20,000 nodes and 1,000,000 text characters, a nesting limit of
32, and strict bounded descriptors. These limits include repeated names before map hashing.
DEX default indexing has its own bounded method/element counts and aggregate text budget, including
repeated lookup keys. Provider failures and malformed payloads remain inspectable; cancellation
propagates. Containers are copied so later input-provider mutation cannot alter the typed snapshot.
This does not change pre-existing reads of class annotations for structural nesting/access flags.

## Remaining work

Neither backend consumes these attributes yet. Java annotation declarations currently emit an
illegal `extends Annotation`; Kotlin declarations currently emit prohibited member functions.
Attachments and defaults are still absent from generated source. This foundation must not be
reported as annotation source parity or an output accuracy gain.

Emission needs exact declaration/use-site placement, default values, legal Kotlin primary-constructor
annotation parameters and annotation-context Class/KClass conversion. Reconstruction must preserve
annotations on the same JVM members. Retention defaults differ between Java and Kotlin; invisible
classfile annotations, repeatable/inherited annotations and compiler-owned Kotlin metadata require
separate validation. Blindly replaying stale `kotlin.Metadata` is not a valid solution.

The javac-to-DEX integration test proves runtime attachments/defaults reach typed IR. The pinned D8
conversion strips the fixture's CLASS-retained attachment before the model sees it; that absence is
asserted explicitly, not counted as successful preservation. A raw DEX test and a pipeline test
separately check BUILD visibility. Source-emission tests must inspect classfiles as well as runtime
reflection. Encoded FLOAT/DOUBLE currently arrive as host numeric values; the new IR retains their
available bits but cannot recover NaN payloads already changed by the input plugin or host.
