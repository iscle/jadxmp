# Invalid bytecode evidence: `TestTryCatchMultiException2`

`corpus/smali/trycatch/TestTryCatchMultiException2.smali` is rejected by Android's
runtime verifier. The pinned jadx reference emits compilable Java for it, but
that Java's `catch (Throwable) { return false; }` is not an execution oracle for
the original DEX. This document records the evidence; it **does not change the
fixture, scoreboard, failure threshold, or documented-divergence list**.

## Input identity

- Original jadx revision: `0e232fb3510ec86083af0055470163d3550957cd`.
- Pin: [`tools/jadx-reference/baseline.properties`](../tools/jadx-reference/baseline.properties).
- Original input: `reference/jadx/jadx-core/src/test/smali/trycatch/TestTryCatchMultiException2.smali`.
- Imported input: [`corpus/smali/trycatch/TestTryCatchMultiException2.smali`](../corpus/smali/trycatch/TestTryCatchMultiException2.smali).
- SHA-256 of both files: `eaad95f9be98a119cf8aaf61ca7c736dc9ed4a7271f03d0e0966e856e0536301`.

Only the fenced smali input was inspected. No upstream decompiler implementation
was copied to diagnose or repair the SSA behavior.

## Conflicting register states

The method declares a boolean result, and its catch-all returns register `v0`.
The protected range begins before `v0` has any definition:

```smali
:try_start_b
const-string v0, "c"
invoke-static {v0}, Ljava/lang/Class;->forName(Ljava/lang/String;)Ljava/lang/Class;
move-result-object v1
const/4 v0, 0x0
# ... more potentially throwing instructions ...
:try_end_2f
.catchall {:try_start_b .. :try_end_2f} :catchall_30
# ... normal boolean return ...
:catchall_30
return v0
```

The incoming states are distinct:

| Exceptional transfer point | Value of `v0` entering the handler |
| --- | --- |
| Failure resolving/loading the first string | Undefined: its assignment has not completed |
| `Class.forName("c")` fails | The string `"c"`, not a boolean |
| A later operation fails after `const/4 v0, 0x0` | Zero, which can represent `false` |

Reading the protected block's final register state on **every** exception edge
incorrectly replaces the first two states with the third. Correct SSA must retain
the state at each throwing instruction and must not commit a result when its
instruction throws. Splitting unrelated string and numeric source locals cannot
make the handler's reference/undefined return a valid boolean return.

## Direct ART observation

On 2026-10-06, a minimal caller and the **unchanged** fixture were assembled using
the oracle's existing smali dependency and its normal API level, 27. Running that
DEX with ART on an existing emulator reporting Android API **37** produced:

```text
Calling original unmodified fixture
Exception in thread "main" java.lang.VerifyError: Verifier rejected class trycatch.TestTryCatchMultiException2: boolean trycatch.TestTryCatchMultiException2.test() failed to verify: boolean trycatch.TestTryCatchMultiException2.test(): [0x1C] register v0 has type Undefined but expected Boolean return-1nr on invalid register v0 (declaration of 'trycatch.TestTryCatchMultiException2' appears in /data/local/tmp/jadxmp-art-verification.Nrp5ZX.dex)
	at Probe.main(dex-id-2eefe90699f98552ac2ef7bc59e7cdf98ade04a9:7)
```

The verifier rejected the method before the caller could print a returned boolean.
The retained local evidence was `/tmp/jadxmp-art-verification.Nrp5ZX/art.log`;
its complete contents are quoted above. The temporary device DEX was removed.
The observation is from this ART version; it does not claim that every historical
Dalvik/ART release was tested.

## Reproduce without changing the repository input

Run from the repository root with a JDK supporting Java source-file execution,
Gradle's configured JDK toolchain, Android platform tools, and a running Android
emulator/device. Select its serial in `JADXMP_ADB_SERIAL`; the observed serial was
`emulator-5554`. These commands derive the classpath from `tools:oracle`, rather
than selecting a different assembler dependency.

```sh
./gradlew :tools:jadx-reference:verifyBaseline
shasum -a 256 corpus/smali/trycatch/TestTryCatchMultiException2.smali

export JADXMP_VERIFY_DIR="$(mktemp -d /tmp/jadxmp-art-verification.XXXXXX)"
mkdir "$JADXMP_VERIFY_DIR/smali"
cp corpus/smali/trycatch/TestTryCatchMultiException2.smali "$JADXMP_VERIFY_DIR/smali/"
cmp corpus/smali/trycatch/TestTryCatchMultiException2.smali "$JADXMP_VERIFY_DIR/smali/TestTryCatchMultiException2.smali"

cat > "$JADXMP_VERIFY_DIR/runtime.init.gradle" <<'GRADLE'
gradle.projectsEvaluated {
    def oracleProject = gradle.rootProject.project(':tools:oracle')
    gradle.rootProject.tasks.register('writeVerificationClasspath') {
        doLast {
            new File(System.getenv('JADXMP_VERIFY_DIR'), 'classpath.txt').text =
                oracleProject.configurations.runtimeClasspath.asPath
        }
    }
}
GRADLE
./gradlew --no-configuration-cache -I "$JADXMP_VERIFY_DIR/runtime.init.gradle" writeVerificationClasspath

cat > "$JADXMP_VERIFY_DIR/AssembleProbe.java" <<'JAVA'
import com.android.tools.smali.smali.Smali;
import com.android.tools.smali.smali.SmaliOptions;
import java.util.List;
class AssembleProbe {
    public static void main(String[] args) throws Exception {
        SmaliOptions options = new SmaliOptions();
        options.apiLevel = 27;
        options.outputDexFile = args[1];
        if (!Smali.assemble(options, List.of(args[0]))) {
            throw new AssertionError("assembly failed");
        }
    }
}
JAVA

cat > "$JADXMP_VERIFY_DIR/smali/Probe.smali" <<'SMALI'
.class public LProbe;
.super Ljava/lang/Object;
.method public static main([Ljava/lang/String;)V
    .registers 3
    sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
    const-string v1, "Calling original unmodified fixture"
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
    invoke-static {}, Ltrycatch/TestTryCatchMultiException2;->test()Z
    move-result v1
    invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Z)V
    return-void
.end method
SMALI

java --class-path "$(cat "$JADXMP_VERIFY_DIR/classpath.txt")" \
    "$JADXMP_VERIFY_DIR/AssembleProbe.java" \
    "$JADXMP_VERIFY_DIR/smali" "$JADXMP_VERIFY_DIR/probe.dex"

JADXMP_ADB_SERIAL=emulator-5554
JADXMP_REMOTE_DEX="/data/local/tmp/$(basename "$JADXMP_VERIFY_DIR").dex"
adb -s "$JADXMP_ADB_SERIAL" shell getprop ro.build.version.sdk
adb -s "$JADXMP_ADB_SERIAL" push "$JADXMP_VERIFY_DIR/probe.dex" "$JADXMP_REMOTE_DEX"
trap 'adb -s "$JADXMP_ADB_SERIAL" shell rm -f "$JADXMP_REMOTE_DEX" >/dev/null 2>&1' EXIT
adb -s "$JADXMP_ADB_SERIAL" shell chmod 444 "$JADXMP_REMOTE_DEX"
adb -s "$JADXMP_ADB_SERIAL" shell dalvikvm -cp "$JADXMP_REMOTE_DEX" Probe \
    > "$JADXMP_VERIFY_DIR/art.log" 2>&1
JADXMP_ART_STATUS=$?
cat "$JADXMP_VERIFY_DIR/art.log"
printf 'ART exit status: %s\n' "$JADXMP_ART_STATUS"
adb -s "$JADXMP_ADB_SERIAL" shell rm -f "$JADXMP_REMOTE_DEX"
```

A verifier rejection is the expected result, not a successful return of `false`.
Temporary directory names and the DEX identifier in the stack trace may differ.
If the shell has `errexit` enabled, disable it around the `dalvikvm` command to
capture its expected nonzero status; the cleanup trap still removes the device
file if the shell exits.

The pinned Java comparison remains reproducible with:

```sh
./gradlew :tools:oracle:smaliScoreboard \
    -Djadxmp.smali.categories=trycatch \
    -Djadxmp.smali.dump=TestTryCatchMultiException2
```

With correct exception SSA, this row currently fails `no-error` and `recompiles`:
jadxmp reports the proven invalid reference-to-primitive return instead of silently
using a later register assignment. The enforced gate remains red. Resolving how
verifier-invalid corpus inputs should be assessed is a separate, explicit oracle
policy decision; this evidence does not authorize suppressing that failure.
