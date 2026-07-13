# corpus MANIFEST

Catalogue of imported test inputs. All derived from skylot/jadx (Apache-2.0),
copied from `reference/jadx/jadx-core/src/test/`. Regenerate counts with:

```sh
find corpus/smali  -name '*.smali' | wc -l
find corpus/raung  -name '*.raung' | wc -l
find corpus/binary -name '*.dex' -o -name '*.apk'
```

## Summary

| Category | Count | Source under `reference/jadx/jadx-core/src/test/` |
|---|---:|---|
| smali (`.smali`) | 210 | `smali/**` |
| raung (`.raung`) |   9 | `raung/**` |
| dex binary (`.dex`) |   1 | `resources/test-samples/hello.dex` |
| apk binary (`.apk`) |   1 | `resources/test-samples/app-with-fake-dex.apk` |

Not yet imported (see `JAVA-SAMPLES.md`): **454** embedded Java `TestCls`
samples (**120** of them carry a `check()` method) — these need a JVM compile
step before they can join the corpus as `.class`/`.dex` fixtures.

## smali/ by construct

| Group | `.smali` |
|---|---:|
| arith | 3 |
| arrays | 3 |
| conditions | 25 |
| debuginfo | 1 |
| enums | 10 |
| fallback | 1 |
| generics | 5 |
| inline | 14 |
| inner | 15 |
| invoke | 4 |
| loops | 8 |
| names | 19 |
| others | 45 |
| rename | 1 |
| special | 3 |
| switches | 2 |
| synchronize | 4 |
| trycatch | 16 |
| types | 25 |
| variables | 6 |
| **total** | **210** |

## raung/ by construct

| Group | `.raung` |
|---|---:|
| enums | 1 |
| java8 | 1 |
| jbc | 1 |
| loops | 1 |
| others | 5 |
| **total** | **9** |

## binary/

| File | Bytes | Notes |
|---|---:|---|
| `hello.dex` | 748 | Single class `HelloWorld` with a `main` printing "Hello world!". The oracle smoke test. |
| `app-with-fake-dex.apk` | 10030 | Tiny APK: 3 classes + resources (ARSC, `colors.xml`, manifest). Exercises container + resource paths. |

## Notes

- The DEX front-end (`core:input-dex`) is being hardened separately; smali/raung
  inputs remain source-form until a JVM assemble helper turns representative ones
  into checked-in `.dex`/`.class` fixtures. `binary/` is directly usable now.
- jadx's upstream count for smali is often cited as ~221; this checkout yields
  210 `.smali` files. Counts above reflect what is actually present in
  `reference/jadx` at import time — trust the numbers here, not the round figure.
</content>
