# Kotlin Throwable source projection

The backend projects the exact JVM `getMessage()Ljava/lang/String;` contract onto Kotlin's
`message` property for `java.lang.Throwable`, `java.lang.Exception`, `java.lang.RuntimeException`,
and loaded subclasses whose superclass chain reaches one of those platform declarations.
The declaration remains a JVM `getMessage` method, with its original visibility, final/open
modality and body. Nullable return contracts remain nullable. Super calls require the encoded
owner to be the immediate superclass; execution tests inspect the compiled `invokespecial`
owner as well as results and effects. Unrelated methods and overloads retain function syntax.

The existing secondary-constructor proof also admits those three exact platform owners with
`()` / `(String)` / `(Throwable)` / `(String, Throwable)` / `(String, Throwable, boolean, boolean)`
constructor descriptors. Loaded declarations take precedence. This extends the existing proof;
it does not invent classpath declarations or move effectful prefixes into Kotlin headers.
Every constructor in the class must still satisfy the proof, including acyclic `this` chains.

Projection analysis is per output and shared between import discovery and real rendering.
It reads declaration metadata, never another output's mutable method bodies. A shared one-million
work budget charges names before lookup/sanitization and member visits before scanning, with
at most 1,024 superclass nodes per lineage query. Retained successful proofs consume budget;
exhaustion cannot grow caches and does not discard previously established declaration proofs.
Unknown ancestors are not evidence of a Throwable contract. Additional interface contracts,
renamed/incompatible getter declarations, conflicting fields named `message`, and native accessors
remain explicit unsupported diagnostics rather than silently changing the JVM method identity.

`KotlinThrowableOverrideSemanticsTest` compares original JVM execution, the original pinned jadx
oracle, and rebuilt Kotlin. It retains the exact `others/TestExplicitOverride.smali` bytes and
SHA-256 `5983d276292e4814a3a2dc20d7ac44ff0aeee8d211c468334e72c47fb306f4dc`.
The matrix checks null messages, virtual and super dispatch, final overrides, unrelated/overloaded
methods, side effects, constructor cause identity, suppression and stack-trace controls.

Two measured boundaries remain visible:

- D8 lowers javac synchronized methods to explicit monitor bodies. The Kotlin output preserves
  the lock (checked with `Thread.holdsLock`), but does not reconstruct `ACC_SYNCHRONIZED` from
  that body. A raw synchronized declaration uses a getter-targeted JVM annotation.
- If a referenced loaded owner's classfile is deliberately removed at runtime, a Kotlin `!!`
  would throw `NullPointerException` before the original JVM method resolution throws
  `NoClassDefFoundError`. Newly projected calls through such owners therefore require an explicit
  definitely-non-null receiver (currently `this` or a direct constructed expression); other calls
  produce a visible unsupported diagnostic and preserve healthy sibling methods. A fresh-loader
  control requires the original/reference linkage error and the candidate diagnostic. Calls through
  the three exact bootstrap owners do not have this missing-owner boundary. The existing general
  Kotlin nullable-invocation linkage gap remains outside this slice.

Annotation preservation is a separate, pending feature. In particular JVM METHOD annotations on
this projected declaration belong to the getter, not to the Kotlin property metadata target.
