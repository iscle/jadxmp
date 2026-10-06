# Java compilation profile and package descriptors

The JVM oracle compiles original Java fixtures, pinned jadx output and jadxmp output through the same
`JavaCompilation` implementation. Its explicit profile is `javac options=-proc:none -Xpkginfo:always`;
fixture compilation may also select a Java release and an explicit dependency classpath. Java
scoreboards and expanded round-trip reports record this profile. Kotlin's compiler options are unchanged.

`-Xpkginfo:always` requests the real `package-info.class` artifact for a `package-info.java` compilation
unit even when it has no annotations. The [javac option contract](https://docs.oracle.com/en/java/javase/26/docs/specs/man/javac.html#extra-options-for-javac)
describes this behavior. The harness continues to require a successful compiler result **and the exact
expected binary artifact for every supplied unit**. Empty/comment-only sources, a mismatched package,
and an ordinary class unit containing only a package statement still fail. No source annotation,
dependency, stub, exception policy or output-class exemption is introduced.

## Pinned fixture evidence

The original jadx commit remains `0e232fb3510ec86083af0055470163d3550957cd`.
`jadx-core/src/test/java/jadx/tests/integration/special/TestPackageInfoSupport.java` explicitly disables
compilation and loads the three smali files from `special/TestPackageInfoSupport`. It checks package
source spelling, not recompilation. Those inputs do not provide the external `ApiStatus.Internal`
annotation dependency.

| Input | Original pinned source | Previous default javac measurement | Explicit package-descriptor profile |
|---|---|---|---|
| `pkg1.smali` | `@Deprecated` on `package special.pkg1` | Compiles | Compiles |
| `pkg2.smali` | `@ApiStatus.Internal` on `package special.pkg2` | Fails with the missing dependency under the Android-only classpath | Still fails with that missing dependency |
| `pkg3.smali` | Only `package special.pkg3;` | javac accepts the source but emits no class; the exact-output check fails | javac emits `special/pkg3/package-info.class`; the same exact-output check passes |

The `pkg3` pinned source and no-error signal do not change. The compiler-profile change applies to both
reference and candidate, so it must not be presented as a decompiler accuracy gain. Tests retain the
legacy no-artifact observation beside the new artifact and assert its actual JVM class identity.
This measures source recompilation and artifact identity, not every original class access flag.

Before package-annotation emission, jadxmp instead produced a renamed empty `package_info` interface
and dropped the package attachment. That unrelated class compiled for `pkg2` and `pkg3`, producing
spurious compilation improvements. A retained test compiles this previous source shape and proves it
produces `package_info.class`, not the required `package-info.class`. Restoring annotations must not
retain that apparent gain by dropping an unresolved attachment.

The package fixtures are not regrouped, and the absent external annotation is not fabricated. Missing
canonical nesting metadata and source-name binding remain code-generation questions, independent of
this compiler option. Old reports without the profile line retain their historical signals; comparisons
must identify the compiler-profile difference rather than attributing it to the engine alone.
