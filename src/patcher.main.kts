#!/usr/bin/env kotlin
/*
 * Builds TIDAL for Google TV with the TIDAL Connect sender enabled: compiles src/patch/ against
 * the stock APK and merges it in (see README.md). Run with --help for options.
 * The name must end in .main.kts for @file:DependsOn.
 */

@file:Repository("https://repo.maven.apache.org/maven2/")
@file:Repository("https://maven.google.com/")
// ASM via Byte Buddy's relocated copy: kotlin-main-kts.jar ships a stripped org.objectweb.asm.
@file:DependsOn("net.bytebuddy:byte-buddy:1.17.5")
@file:DependsOn("com.android.tools.smali:smali-dexlib2:3.0.10")

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes.ACC_SYNTHETIC
import net.bytebuddy.jar.asm.Opcodes.ASM9
import net.bytebuddy.jar.asm.Opcodes.V1_8
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.Remapper
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

val ROOT: File = __FILE__.absoluteFile.parentFile.parentFile   // script is in src/
val SRC = File(ROOT, "src/patch")
val BUILD = File(ROOT, "build")

// The build src/patch/ is written for; its R8 names change with every release.
val TIDAL_PACKAGE = "com.aspiro.tidal"
val TIDAL_VERSION_NAME = "2.214.0"
val TIDAL_VERSION_CODE = "10018"
val MIN_API = 32            // stock minSdkVersion
val JVM_TARGET = "17"
val MOD_PACKAGE = "io/github/resurekto/tidalgtvmod/"   // new classes and bundled stdlib copies
// Left out of the compile classpath: kotlinc brings its own stdlib.
val API_JAR_EXCLUDES = listOf("kotlin/", "META-INF/")

val MAPPING = """
# Kotlin stdlib names inside the app (JVM internal names). Unlisted stdlib classes the
# app lacks are bundled under the mod package.
#   class    <kotlin class> <app name>
#   field    <owner> <name> <app name>
#   method   <owner> <name> <descriptor> <app name>
#   relocate <class>        bundle a copy even if the app has one
#   ignore   <class>        may be missing at runtime

# Unit: TIDAL code compares the singleton.
class  kotlin/Unit  s8i
field  kotlin/Unit  INSTANCE  a

# Lambdas handed to TIDAL code.
class  kotlin/jvm/functions/Function   p37
class  kotlin/jvm/functions/Function0  y27
class  kotlin/jvm/functions/Function1  a37
class  kotlin/jvm/functions/Function2  o37
class  kotlin/jvm/functions/Function4  q37

# Coroutines (kotlinx.coroutines keeps its names).
class  kotlin/coroutines/Continuation      im3
class  kotlin/coroutines/CoroutineContext  kotlin/coroutines/d

# Enum entries.
class  kotlin/enums/EnumEntries  aw5

# invokedynamic bootstraps, desugared by d8.
ignore java/lang/invoke/LambdaMetafactory
ignore java/lang/invoke/StringConcatFactory

# Nullability annotations: compile-time only.
ignore org/jetbrains/annotations/NotNull
ignore org/jetbrains/annotations/Nullable
"""

class ModError(message: String) : RuntimeException(message)

fun die(msg: String): Nothing = throw ModError(msg)

fun run(vararg cmd: Any, quiet: Boolean = false) {
    val argv = cmd.map { it.toString() }
    println("+ " + argv.joinToString(" "))
    val pb = ProcessBuilder(argv).inheritIO()
    // Set by the `kotlin` launcher; inherited, it turns kotlinc into a script runner.
    pb.environment().remove("KOTLIN_RUNNER")
    if (quiet) pb.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
    val code = pb.start().waitFor()
    if (code != 0) die("${File(argv[0]).name} failed (exit $code)")
}

fun needRebuild(output: File, inputs: List<File>) =
    !output.exists() || output.lastModified() < (inputs.maxOfOrNull { it.lastModified() } ?: 0)

// ---- toolchain ----------------------------------------------------------------------

fun findSdk(): File {
    for (env in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) System.getenv(env)?.let { if (it.isNotEmpty()) return File(it) }
    val home = System.getProperty("user.home")
    return listOf(File(home, "Library/Android/sdk"), File(home, "Android/Sdk")).firstOrNull { it.isDirectory }
        ?: die("Android SDK not found: export ANDROID_HOME")
}

fun buildTools(sdk: File): File =
    (File(sdk, "build-tools").listFiles() ?: emptyArray()).sortedByDescending { it.name }
        .firstOrNull { v -> listOf("d8", "zipalign", "apksigner", "aapt2").all { File(v, it).exists() } }
        ?: die("no build-tools with d8, zipalign, apksigner and aapt2 under $sdk")

fun androidJar(sdk: File): File =
    (File(sdk, "platforms").listFiles() ?: emptyArray())
        .map { File(it, "android.jar") }.filter { it.exists() }
        .maxByOrNull { it.parentFile.name.substringAfter("android-").toIntOrNull() ?: 0 }
        ?: die("no platform under $sdk/platforms")

fun which(name: String, hint: String): File =
    System.getenv("PATH").split(File.pathSeparator).map { File(it, name) }.firstOrNull { it.canExecute() }
        ?: die("$name not found on PATH ($hint)")

fun kotlinStdlib(kotlinc: File): File {
    val root = kotlinc.canonicalFile.parentFile.parentFile
    return listOf(File(root, "lib/kotlin-stdlib.jar"), File(root, "libexec/lib/kotlin-stdlib.jar")).firstOrNull { it.exists() }
        ?: die("kotlin-stdlib.jar not found next to $kotlinc")
}

fun javaHomeTool(name: String): File =
    System.getenv("JAVA_HOME")?.let { File(it, "bin/$name") }?.takeIf { it.exists() } ?: which(name, "install a JDK")

/** Refuses any input but the expected TIDAL build, unless [force]. */
fun checkVersion(bt: File, apk: File, force: Boolean) {
    val proc = ProcessBuilder(File(bt, "aapt2").path, "dump", "badging", apk.path).redirectErrorStream(true).start()
    val badging = proc.inputStream.bufferedReader().readText()
    if (proc.waitFor() != 0) die("aapt2 cannot read $apk:\n$badging")
    val pkgLine = badging.lineSequence().firstOrNull { it.startsWith("package:") } ?: die("no package line in aapt2 output for $apk")
    fun attr(name: String) = Regex("""\b$name='([^']*)'""").find(pkgLine)?.groupValues?.get(1) ?: "?"
    val found = "${attr("name")} ${attr("versionName")} (versionCode ${attr("versionCode")})"
    val expected = "$TIDAL_PACKAGE $TIDAL_VERSION_NAME (versionCode $TIDAL_VERSION_CODE)"
    if (found == expected) {
        println("input: $found")
    } else if (force) {
        println("WARNING: input is $found, the mod was written for $expected; continuing because of --force")
    } else {
        die("input is $found, but the mod is for $expected; R8 names differ between builds. --force overrides.")
    }
}

/** Refuses an input this script already patched: it contains classes of the mod package. */
fun checkNotPatched(apk: File) {
    val opcodes = Opcodes.forApi(MIN_API)
    val markers = listOf("L$MOD_PACKAGE", "Lng/")   // ng/: builds before the rename
    ZipFile(apk).use { zip ->
        for (entry in zip.entries()) {
            if (!dexEntry.matches(entry.name)) continue
            val dex = DexBackedDexFile(opcodes, zip.getInputStream(entry).use { it.readBytes() })
            if (dex.classes.any { c -> markers.any { c.type.startsWith(it) } }) {
                die("$apk is already patched; use the stock APK set from the Play Store build")
            }
        }
    }
}

// ---- app jars -----------------------------------------------------------------------

/** Signatures-only jar of base.apk (cached per APK) and the compile classpath made from it. */
fun prepareAppJars(dex2jar: File, apk: File): Pair<File, File> {
    val out = File(BUILD, "tidal").apply { mkdirs() }
    val mtimeNs = Files.getLastModifiedTime(apk.toPath()).to(TimeUnit.NANOSECONDS)
    val stamp = MessageDigest.getInstance("SHA-1").digest("${apk.canonicalPath}:${apk.length()}:$mtimeNs".toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)
    val classes = File(out, "tidal-classes-$stamp.jar")
    val api = File(out, "tidal-api-$stamp.jar")
    if (!classes.exists()) run(dex2jar, "--no-code", "-f", "-o", classes, apk)
    if (needRebuild(api, listOf(classes, __FILE__))) apiJar(classes, api, API_JAR_EXCLUDES)
    return classes to api
}

/**
 * Compile classpath: [input] minus [excludePrefixes], without kotlin.Metadata (so kotlinc
 * treats the classes as Java), without synthetic flags (javac skips synthetic members), and
 * at least Java 8 (static interface methods).
 */
fun apiJar(input: File, output: File, excludePrefixes: List<String>) {
    var kept = 0
    ZipFile(input).use { zin ->
        ZipOutputStream(output.outputStream().buffered()).use { zout ->
            for (entry in zin.entries()) {
                if (!entry.name.endsWith(".class") || excludePrefixes.any { entry.name.startsWith(it) }) continue
                val writer = ClassWriter(0)
                ClassReader(zin.getInputStream(entry).readBytes()).accept(object : ClassVisitor(ASM9, writer) {
                    override fun visit(version: Int, access: Int, name: String, sig: String?, superName: String?, interfaces: Array<String>?) =
                        super.visit(maxOf(version, V1_8), access and ACC_SYNTHETIC.inv(), name, sig, superName, interfaces)
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                        if (descriptor == "Lkotlin/Metadata;") null else super.visitAnnotation(descriptor, visible)
                    override fun visitMethod(access: Int, name: String, desc: String, sig: String?, ex: Array<String>?): MethodVisitor? =
                        super.visitMethod(access and ACC_SYNTHETIC.inv(), name, desc, sig, ex)
                    override fun visitField(access: Int, name: String, desc: String, sig: String?, value: Any?): FieldVisitor? =
                        super.visitField(access and ACC_SYNTHETIC.inv(), name, desc, sig, value)
                }, 0)
                zout.putNextEntry(ZipEntry(entry.name))
                zout.write(writer.toByteArray())
                zout.closeEntry()
                kept++
            }
        }
    }
    println("api jar: $kept classes")
}

// ---- compile ------------------------------------------------------------------------

fun compileSources(kotlinc: File, javac: File, classpath: List<File>): File {
    val classes = File(BUILD, "classes")
    val kt = SRC.walk().filter { it.isFile && it.extension == "kt" }.sorted().toList()
    val java = SRC.walk().filter { it.isFile && it.extension == "java" }.sorted().toList()
    if (kt.isEmpty() && java.isEmpty()) die("no sources in $SRC")
    classes.deleteRecursively()
    classes.mkdirs()
    val cp = classpath.joinToString(File.pathSeparator)
    if (kt.isNotEmpty()) {
        run(kotlinc, "-nowarn", "-jvm-target", JVM_TARGET, "-no-reflect",
            // The app's Intrinsics has no null-check methods left.
            "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions",
            "-cp", cp, "-d", classes, *kt.toTypedArray())
    }
    if (java.isNotEmpty()) {
        run(javac, "-nowarn", "--release", JVM_TARGET, "-cp", cp + File.pathSeparator + classes, "-d", classes, *java.toTypedArray())
    }
    return classes
}

// ---- remap --------------------------------------------------------------------------

class Mapping {
    val classes = mutableMapOf<String, String>()
    val fields = mutableMapOf<String, String>()   // "owner.name" -> new name
    val methods = mutableMapOf<String, String>()  // "owner.name(desc)" -> new name
    val relocate = mutableSetOf<String>()
    val ignore = mutableSetOf<String>()
}

fun parseMapping(text: String): Mapping {
    val m = Mapping()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEachIndexed
        val p = line.split(Regex("\\s+"))
        fun bad(): Nothing = die("MAPPING line ${i + 1}: cannot parse: $raw")
        when (p[0]) {
            "class" -> if (p.size == 3) m.classes[p[1]] = p[2] else bad()
            "field" -> if (p.size == 4) m.fields["${p[1]}.${p[2]}"] = p[3] else bad()
            "method" -> if (p.size == 5) m.methods["${p[1]}.${p[2]}${p[3]}"] = p[4] else bad()
            "relocate" -> if (p.size == 2) m.relocate += p[1] else bad()
            "ignore" -> if (p.size == 2) m.ignore += p[1] else bad()
            else -> bad()
        }
    }
    return m
}

class JarIndex(file: File) : AutoCloseable {
    private val zip = ZipFile(file)
    private val entries: Map<String, ZipEntry> = buildMap {
        for (e in zip.entries()) if (e.name.endsWith(".class")) putIfAbsent(e.name.removeSuffix(".class"), e)
    }
    fun has(name: String) = name in entries
    fun bytes(name: String): ByteArray? = entries[name]?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
    override fun close() = zip.close()
}

class ClassInfo(val superName: String?, val interfaces: List<String>, val fields: Set<String>, val methods: Set<String>)

fun readClassInfo(bytes: ByteArray): ClassInfo {
    val reader = ClassReader(bytes)
    val fields = mutableSetOf<String>()
    val methods = mutableSetOf<String>()
    reader.accept(object : ClassVisitor(ASM9) {
        override fun visitField(a: Int, name: String, desc: String, s: String?, v: Any?) = null.also { fields += "$name:$desc" }
        override fun visitMethod(a: Int, name: String, desc: String, s: String?, ex: Array<String>?) = null.also { methods += "$name$desc" }
    }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    return ClassInfo(reader.superName, reader.interfaces.toList(), fields, methods)
}

/** Where a referenced class comes from; [mappedTo] is set for [Kind.MAPPED]. */
enum class Kind { MINE, MAPPED, APP, ANDROID, RELOCATED, IGNORED }
class Decision(val kind: Kind, val mappedTo: String? = null)

class MemberRef(val owner: String, val name: String, val desc: String, val isMethod: Boolean, val from: String)

/**
 * Makes the mod classes loadable in the app: renames the stdlib types listed in MAPPING,
 * bundles other needed stdlib classes under [prefix], and fails on any class or member
 * reference that the app, the platform or the bundle does not have.
 */
fun remap(classesDir: File, mappingText: String, appJar: File, androidJar: File, stdlibJar: File, prefix: String, output: File) {
    val mapping = parseMapping(mappingText)
    val mine = linkedMapOf<String, ByteArray>()
    classesDir.walk().filter { it.isFile && it.extension == "class" }.sorted().forEach {
        mine[it.relativeTo(classesDir).path.removeSuffix(".class").replace(File.separatorChar, '/')] = it.readBytes()
    }
    if (mine.isEmpty()) die("no classes in $classesDir")

    JarIndex(appJar).use { app ->
    JarIndex(androidJar).use { android ->
    JarIndex(stdlibJar).use { stdlib ->
        val relocated = linkedMapOf<String, ByteArray>()
        val decided = mutableMapOf<String, Decision>()
        val errors = linkedSetOf<String>()
        val queue = ArrayDeque(mine.keys)

        fun decide(name: String): Decision = decided.getOrPut(name) {
            when {
                name in mine -> Decision(Kind.MINE)
                name in mapping.classes -> Decision(Kind.MAPPED, mapping.classes.getValue(name))
                name in mapping.ignore -> Decision(Kind.IGNORED)
                name in mapping.relocate || (!android.has(name) && !app.has(name)) -> {
                    val bytes = stdlib.bytes(name)
                    if (bytes == null) {
                        errors += "class $name: not in the app, the Android platform or the Kotlin stdlib"
                        Decision(Kind.IGNORED)
                    } else {
                        relocated[name] = bytes
                        queue += name
                        Decision(Kind.RELOCATED)
                    }
                }
                android.has(name) -> Decision(Kind.ANDROID)
                else -> Decision(Kind.APP)
            }
        }
        fun newName(name: String): String = decide(name).let {
            when (it.kind) {
                Kind.MAPPED -> it.mappedTo!!
                Kind.RELOCATED -> prefix + name
                else -> name
            }
        }

        val members = mutableListOf<MemberRef>()
        var current = ""
        val remapper = object : Remapper() {
            override fun map(internalName: String): String = newName(internalName)
            override fun mapFieldName(owner: String, name: String, desc: String): String {
                members += MemberRef(owner, name, desc, false, current)
                return mapping.fields["$owner.$name"] ?: name
            }
            override fun mapMethodName(owner: String, name: String, desc: String): String {
                if (name != "<init>" && name != "<clinit>") members += MemberRef(owner, name, desc, true, current)
                return mapping.methods["$owner.$name$desc"] ?: name
            }
        }

        // Pass 1: collect referenced classes (relocating transitively) and members.
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            current = name
            ClassReader(mine[name] ?: relocated.getValue(name)).accept(ClassRemapper(ClassWriter(0), remapper), 0)
        }

        // Pass 2: resolve every member. Mod classes name supertypes by original names, app
        // classes by app names.
        val infos = mutableMapOf<String, ClassInfo?>()
        fun info(name: String, original: Boolean): ClassInfo? = infos.getOrPut((if (original) "o:" else "a:") + name) {
            val bytes = if (original) {
                val d = decide(name)
                when (d.kind) {
                    Kind.MINE -> mine[name]
                    Kind.RELOCATED -> relocated[name]
                    Kind.MAPPED -> app.bytes(d.mappedTo!!) ?: android.bytes(d.mappedTo)
                    Kind.APP -> app.bytes(name)
                    Kind.ANDROID -> android.bytes(name)
                    Kind.IGNORED -> null
                }
            } else app.bytes(name) ?: android.bytes(name)
            bytes?.let { readClassInfo(it) }
        }
        fun has(name: String, original: Boolean, member: String, memberMapped: String, seen: MutableSet<String> = mutableSetOf()): Boolean {
            if (!seen.add((if (original) "o:" else "a:") + name)) return false
            if (original && decide(name).kind.let { it == Kind.ANDROID || it == Kind.IGNORED }) return true
            if (!original && android.has(name)) return true
            val switchToApp = original && decide(name).kind.let { it == Kind.MAPPED || it == Kind.APP }
            val ci = info(name, original) ?: return false
            val m = if (original && !switchToApp) member else memberMapped
            if (m in ci.fields || m in ci.methods) return true
            val ns = original && !switchToApp
            return (listOfNotNull(ci.superName) + ci.interfaces).any { has(it, ns, member, memberMapped, seen) }
        }
        for (ref in members) {
            val d = decide(ref.owner)
            if (d.kind == Kind.ANDROID || d.kind == Kind.IGNORED || d.kind == Kind.RELOCATED) continue
            val mappedName = if (ref.isMethod) mapping.methods["${ref.owner}.${ref.name}${ref.desc}"] ?: ref.name
                             else mapping.fields["${ref.owner}.${ref.name}"] ?: ref.name
            val member = if (ref.isMethod) ref.name + ref.desc else "${ref.name}:${ref.desc}"
            val memberMapped = if (ref.isMethod) mappedName + remapper.mapMethodDesc(ref.desc) else "$mappedName:${remapper.mapDesc(ref.desc)}"
            if (!has(ref.owner, true, member, memberMapped)) {
                val where = if (d.kind == Kind.MAPPED) "${ref.owner} -> ${d.mappedTo}" else ref.owner
                errors += "${if (ref.isMethod) "method" else "field"} $where.$mappedName ${remapper.mapDesc(ref.desc)} (referenced from ${ref.from}): not found"
            }
        }
        if (errors.isNotEmpty()) {
            die("missing references (fix the code or add a MAPPING line):\n  " + errors.joinToString("\n  "))
        }

        // Pass 3: write the remapped classes.
        var written = 0
        ZipOutputStream(output.outputStream().buffered()).use { zout ->
            fun write(name: String, bytes: ByteArray) {
                val writer = ClassWriter(0)
                // kotlin.Metadata is compiler-only, and d8 rejects newer versions.
                val noMetadata = object : ClassVisitor(ASM9, writer) {
                    override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                        if (descriptor == "Lkotlin/Metadata;") null else super.visitAnnotation(descriptor, visible)
                }
                ClassReader(bytes).accept(ClassRemapper(noMetadata, remapper), 0)
                zout.putNextEntry(ZipEntry(newName(name) + ".class"))
                zout.write(writer.toByteArray())
                zout.closeEntry()
                written++
            }
            for ((name, bytes) in mine) write(name, bytes)
            for ((name, bytes) in relocated.toList()) write(name, bytes)
        }
        check(written == mine.size + relocated.size)
        println("mod classes: ${mine.size} compiled, ${relocated.size} relocated from the Kotlin stdlib")
        val mapped = decided.filterValues { it.kind == Kind.MAPPED }.keys.sorted()
        if (mapped.isNotEmpty()) println("  mapped to app names: " + mapped.joinToString())
        if (relocated.isNotEmpty()) println("  relocated: " + relocated.keys.sorted().joinToString())
    }}}
}

// ---- merge --------------------------------------------------------------------------

val signatureFiles = Regex("""^(META-INF/[^/]+\.(SF|RSA|DSA|EC|MF)|stamp-cert-sha256)$""")
val dexEntry = Regex("""classes(\d*)\.dex""")

/**
 * Copies [src] to [dst], dropping the classes [modDex] replaces and the old signature, and adds
 * [modDex] as the next classesN.dex. Entry compression is kept.
 */
fun merge(src: File, modDex: File, minApi: Int, dst: File) {
    val opcodes = Opcodes.forApi(minApi)
    val modTypes = DexBackedDexFile(opcodes, modDex.readBytes()).classes.map { it.type }.toSet()
    val replaced = mutableSetOf<String>()
    var highest = 0
    val work = File(dst.parentFile, "dex-work").apply { mkdirs() }

    ZipFile(src).use { zin ->
        ZipOutputStream(dst.outputStream().buffered()).use { zout ->
            for (entry in zin.entries()) {
                if (signatureFiles.matches(entry.name)) continue
                var data = zin.getInputStream(entry).use { it.readBytes() }
                dexEntry.matchEntire(entry.name)?.let { m ->
                    highest = maxOf(highest, m.groupValues[1].ifEmpty { "1" }.toInt())
                    val dex = DexBackedDexFile(opcodes, data)
                    val hits = dex.classes.filter { it.type in modTypes }
                    if (hits.isNotEmpty()) {
                        replaced += hits.map { it.type }
                        val out = File(work, entry.name)
                        DexPool.writeTo(FileDataStore(out), ImmutableDexFile(opcodes, dex.classes.filter { it.type !in modTypes }))
                        data = out.readBytes()
                        println("${entry.name}: removed ${hits.size} classes replaced by the mod: " +
                            hits.joinToString { it.type.removePrefix("L").removeSuffix(";") })
                    }
                }
                writeEntry(zout, entry.name, entry.method, entry.time, data)
            }
            val name = "classes${highest + 1}.dex"
            writeEntry(zout, name, ZipEntry.DEFLATED, 347155262000L, modDex.readBytes())
            println("added $name with ${modTypes.size} classes (${replaced.size} replace app classes, ${modTypes.size - replaced.size} are new)")
        }
    }
}

fun writeEntry(zout: ZipOutputStream, name: String, method: Int, time: Long, data: ByteArray) {
    zout.putNextEntry(ZipEntry(name).apply {
        this.method = method
        this.time = time
        if (method == ZipEntry.STORED) {
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
    })
    zout.write(data)
    zout.closeEntry()
}

// ---- signing ------------------------------------------------------------------------

fun sign(bt: File, keystore: File, ksPass: String, ksAlias: String, unsigned: File, signed: File) {
    val aligned = File(unsigned.parentFile, "aligned-" + unsigned.name)
    run(File(bt, "zipalign"), "-f", "-p", "4", unsigned, aligned)
    run(File(bt, "apksigner"), "sign", "--ks", keystore, "--ks-pass", "pass:$ksPass",
        "--ks-key-alias", ksAlias, "--out", signed, aligned)
    run(File(bt, "apksigner"), "verify", signed)
}

fun ensureKeystore(keytool: File, keystore: File, ksPass: String, ksAlias: String) {
    if (keystore.exists()) return
    keystore.parentFile.mkdirs()
    run(keytool, "-genkeypair", "-keystore", keystore, "-storepass", ksPass, "-alias", ksAlias,
        "-keypass", ksPass, "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
        "-dname", "CN=Android Debug,O=Android,C=US")
}

// ---- main ---------------------------------------------------------------------------

val USAGE = """
    usage: ./src/patcher.main.kts --in FILE --out FILE [options]

      --in FILE         the stock base.apk; its split_config.*.apk are taken from the same directory
      --out FILE        the modded base.apk to write; the re-signed splits go next to it

    optional:
      --install         adb install-multiple -r the result
      --serial SERIAL   adb device serial (with --install/--launch)
      --launch          start TIDAL on the device afterwards
      --keystore FILE   default: ~/.android/debug.keystore
      --ks-pass PASS    default: android
      --ks-alias ALIAS  default: androiddebugkey
      --dex2jar CMD     dex2jar command (default: d2j-dex2jar)
      --force           build even if the input is not TIDAL $TIDAL_VERSION_NAME ($TIDAL_VERSION_CODE)
""".trimIndent()

class Options {
    var input: File? = null
    var output: File? = null
    var install = false
    var launch = false
    var serial: String? = null
    var keystore = File(System.getProperty("user.home"), ".android/debug.keystore")
    var ksPass = "android"
    var ksAlias = "androiddebugkey"
    var dex2jar = "d2j-dex2jar"
    var force = false
}

fun parseArgs(argv: Array<String>): Options {
    val o = Options()
    val it = argv.iterator()
    fun value(flag: String): String = if (it.hasNext()) it.next() else die("$flag needs a value\n$USAGE")
    while (it.hasNext()) {
        when (val a = it.next()) {
            "--in" -> o.input = File(value(a))
            "--out" -> o.output = File(value(a))
            "--install" -> o.install = true
            "--launch" -> o.launch = true
            "--serial" -> o.serial = value(a)
            "--keystore" -> o.keystore = File(value(a))
            "--ks-pass" -> o.ksPass = value(a)
            "--ks-alias" -> o.ksAlias = value(a)
            "--dex2jar" -> o.dex2jar = value(a)
            "--force" -> o.force = true
            "-h", "--help" -> { println(USAGE); exitProcess(0) }
            else -> die("unknown argument: $a\n$USAGE")
        }
    }
    return o
}

fun main(argv: Array<String>) {
    val o = parseArgs(argv)
    val apk = o.input ?: die("--in is required\n$USAGE")
    val outApk = (o.output ?: die("--out is required\n$USAGE")).absoluteFile
    if (!apk.isFile) die("$apk does not exist")
    if (outApk.extension != "apk") die("--out must name an .apk file: $outApk")
    // Splits are written next to the output; never over the stock ones.
    if (outApk.parentFile.canonicalFile == apk.absoluteFile.parentFile.canonicalFile) {
        die("--out must be in a different directory than --in")
    }
    val splits = (apk.absoluteFile.parentFile.listFiles() ?: emptyArray())
        .filter { it.name.startsWith("split_config.") && it.name.endsWith(".apk") }.sortedBy { it.name }

    val sdk = findSdk()
    val bt = buildTools(sdk)
    checkVersion(bt, apk, o.force)
    checkNotPatched(apk)
    val android = androidJar(sdk)
    val kotlinc = which("kotlinc", "brew install kotlin")
    val dex2jar = which(o.dex2jar, "brew install dex2jar")
    val javac = javaHomeTool("javac")
    val keytool = javaHomeTool("keytool")
    val stdlib = kotlinStdlib(kotlinc)

    val (appClasses, appApi) = prepareAppJars(dex2jar, apk)

    val classes = compileSources(kotlinc, javac, listOf(android, appApi))
    val modJar = File(BUILD, "mod-classes.jar")
    remap(classes, MAPPING, appClasses, android, stdlib, MOD_PACKAGE, modJar)

    val dexDir = File(BUILD, "dex")
    dexDir.deleteRecursively()
    dexDir.mkdirs()
    run(File(bt, "d8"), "--release", "--min-api", MIN_API, "--lib", android, "--classpath", appClasses,
        "--output", dexDir, modJar)

    outApk.parentFile.mkdirs()
    val work = File(BUILD, "apk").apply { mkdirs() }
    ensureKeystore(keytool, o.keystore, o.ksPass, o.ksAlias)
    val unsigned = File(work, "unsigned-base.apk")
    merge(apk, File(dexDir, "classes.dex"), MIN_API, unsigned)
    val outputs = mutableListOf(outApk)
    sign(bt, o.keystore, o.ksPass, o.ksAlias, unsigned, outputs[0])
    for (split in splits) {
        val copy = File(work, "unsigned-" + split.name)
        split.copyTo(copy, overwrite = true)
        outputs += File(outApk.parentFile, split.name)
        sign(bt, o.keystore, o.ksPass, o.ksAlias, copy, outputs.last())
    }

    println("\nsigned APKs:\n  " + outputs.joinToString("\n  "))
    val adb = File(sdk, "platform-tools/adb")
    val adbCmd = listOf<Any>(adb) + (o.serial?.let { listOf("-s", it) } ?: emptyList())
    if (o.install) run(*(adbCmd + listOf("install-multiple", "-r") + outputs).toTypedArray())
    else println("install: $adb install-multiple -r ${outputs.joinToString(" ")}")
    if (o.launch) {
        run(*(adbCmd + listOf("shell", "monkey", "-p", "com.aspiro.tidal", "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")).toTypedArray(),
            quiet = true)
    }
}

try {
    main(args)
} catch (e: ModError) {
    System.err.println("patcher.main.kts: ${e.message}")
    exitProcess(1)
}
