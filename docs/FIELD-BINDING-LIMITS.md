# Field owner binding: supported proof and remaining gaps

The native field slice supports mutable fields declared on the current class. Its shared emission
prerequisite applies to both DEX and JVM input: exact owner, name and descriptor must match one
non-final declaration without ConstantValue. Java body references may use the declared field name
when no local captures it. Kotlin static bodies may use `this.field` only inside their actual
companion/object property container. Owned instance references require complete scope and a reserved
import alias; complete metadata alone does not make an inherited type name harmless. This does not
establish general foreign-owner binding.

Foreign references and enum/header/hoisted-expression contexts retain the earlier emission path.
All owned-proof requests charge owner/type comparison work before equality; exhausted work receives
a diagnostic instead of continuing unbounded resolution, including when ownership cannot be decided.
In particular, an import or typed-null qualifier does not by itself prove the selected class.
An unloaded ancestor can introduce either a field or an inherited member type with the same name.
An allocator cannot reserve names from metadata it has never inspected.

## Concrete unresolved foreign-type capture

This reproducible Java lookup probe prints `7` for the original qualified expression and `99` for
the shortened expression. `javap -c p.Caller` shows that the original read names `p/Owner.value`;
shortening the owner changes the symbolic target to `p/Base$Owner.value`. Both versions compile.
The current foreign-owner path has no complete hierarchy/type-scope proof; this is pending work,
not a passing parity control. The probe does not claim every decompiled file chooses this spelling.

```sh
probe_dir=$(mktemp -d)
mkdir -p "$probe_dir/src/p" "$probe_dir/original" "$probe_dir/shortened"
cat > "$probe_dir/src/p/Base.java" <<'JAVA'
package p;
public class Base {
    public static class Owner { public static int value = 99; }
}
JAVA
cat > "$probe_dir/src/p/Owner.java" <<'JAVA'
package p;
public class Owner { public static int value = 7; }
JAVA
cat > "$probe_dir/src/p/Caller.java" <<'JAVA'
package p;
public class Caller extends Base {
    public static int read() { return p.Owner.value; }
    public static void main(String[] args) { System.out.println(read()); }
}
JAVA
javac -d "$probe_dir/original" "$probe_dir"/src/p/*.java
java -cp "$probe_dir/original" p.Caller
javap -classpath "$probe_dir/original" -c p.Caller
sed 's/return p.Owner.value/return Owner.value/' "$probe_dir/src/p/Caller.java" > "$probe_dir/Caller.java"
javac -cp "$probe_dir/original" -d "$probe_dir/shortened" "$probe_dir/Caller.java"
java -cp "$probe_dir/shortened:$probe_dir/original" p.Caller
```

A typed-null expression `((Owner) null).value` has the same inherited-type lookup problem. A Kotlin
import alias can instead be captured by an inherited value with that alias. Retrying guessed names
or universally using a fully qualified spelling is insufficient: package-root type names can also
be shadowed. The committed constructor-name prerequisite uses complete lexical/member-scope proof;
foreign field emission needs its own access and source-context proof before it can be generalized.

## Why the field change stays narrow

An unlanded broad resolver diagnosed all incomplete ancestor metadata. Against the unchanged
original jadx pin `0e232fb3510ec86083af0055470163d3550957cd`, it produced six additional Java
no-error regressions in valid missing-dependency corpus inputs:

- `conditions/TestOutBlock`
- `generics/TestSyntheticOverride/TestSyntheticOverride`
- `inline/TestSyntheticInline3/TestSyntheticInline3$onCreate$1`
- `inner/TestAnonymousClass19/Lambda$TestCls$1`
- `inner/TestNestedAnonymousClass/C`
- `variables/TestVariables6`

For example, the original fixtures reference absent proprietary ancestors and Android resource
owners; complete metadata cannot be assumed. That broad resolver was not landed. The final owned
path leaves foreign emission unchanged, keeps the original corpus policy and denominator, and
retains this counterevidence. Its common boundary tests assert only unchanged routing, not foreign
semantic correctness.

`NativeJvmFieldOwnerNameTest` separately verifies actual owned fields against unloaded ancestor
field/type shadows, class initialization and effects. Static-only classes execute in both outputs.
The Kotlin instance-method case with incomplete scope remains explicitly diagnosed and fails
recompilation; its healthy sibling and static methods are preserved. Final/ConstantValue native
accesses remain unsupported so source constant folding cannot erase JVM initialization effects.
