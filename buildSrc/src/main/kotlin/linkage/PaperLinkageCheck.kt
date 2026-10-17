package linkage

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.zip.ZipFile

/**
 * Fails on every method, field or class reference from the plugin's classes into the server-provided API packages
 * that does not resolve, by name and descriptor through the owner's supertypes, against the baseline server API.
 */
@CacheableTask
abstract class PaperLinkageCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:Classpath
    abstract val baseline: ConfigurableFileCollection

    /** Internal-name prefixes whose references must resolve against [baseline]. */
    @get:Input
    abstract val packages: SetProperty<String>

    /** Internal-name prefixes inside [packages] that the baseline does not carry: server internals and bundled libraries. */
    @get:Input
    abstract val excluded: SetProperty<String>

    /** Failure lines (`class.method -> owner.name descriptor`) mapped to the reason they are tolerated. */
    @get:Input
    abstract val allowed: MapProperty<String, String>

    @TaskAction
    fun check() {
        val pool = ClassPool()
        baseline.files.forEach { pool.addArchive(it) }
        val scanned = mutableMapOf<String, ByteArray>()
        classes.files.forEach { root -> readClasses(root) { name, bytes -> scanned[name] = bytes } }
        // Plugin classes extending server types resolve inherited members through the server's supertypes.
        scanned.forEach { (name, bytes) -> pool.add(name, bytes) }

        val prefixes = packages.get()
        val skipped = excluded.get()
        fun inScope(owner: String) = prefixes.any { owner.startsWith(it) } && skipped.none { owner.startsWith(it) }
        val failures = sortedSetOf<String>()
        for ((name, bytes) in scanned) {
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, n: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    (listOfNotNull(superName) + interfaces.orEmpty()).filter(::inScope).filter { pool.info(it) == null }
                        .forEach { failures += "${name.replace('/', '.')} -> $it (missing class)" }
                }

                override fun visitMethod(access: Int, method: String, desc: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    val site = "${name.replace('/', '.')}.$method"
                    fun type(internal: String) {
                        val owner = Type.getObjectType(internal).let { if (it.sort == Type.ARRAY) it.elementType else it }
                        if (owner.sort == Type.OBJECT && inScope(owner.internalName) && pool.info(owner.internalName) == null) {
                            failures += "$site -> ${owner.internalName} (missing class)"
                        }
                    }
                    fun member(owner: String, member: String, descriptor: String, field: Boolean) {
                        if (owner.startsWith("[") || !inScope(owner)) return
                        val resolved = if (pool.info(owner) == null) false else pool.resolves(owner, member, descriptor, field)
                        if (!resolved) failures += "$site -> $owner.$member $descriptor"
                    }
                    fun handle(h: Handle) = member(h.owner, h.name, h.desc, h.tag <= Opcodes.H_PUTSTATIC)
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(opcode: Int, owner: String, n: String, d: String, isInterface: Boolean) = member(owner, n, d, false)
                        override fun visitFieldInsn(opcode: Int, owner: String, n: String, d: String) = member(owner, n, d, true)
                        override fun visitTypeInsn(opcode: Int, t: String) = type(t)
                        override fun visitLdcInsn(value: Any?) {
                            if (value is Type && value.sort == Type.OBJECT) type(value.internalName)
                            if (value is Handle) handle(value)
                        }
                        override fun visitInvokeDynamicInsn(n: String, d: String, bsm: Handle, vararg args: Any?) {
                            handle(bsm)
                            args.filterIsInstance<Handle>().forEach(::handle)
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }

        val tolerated = allowed.get()
        val unexpected = failures.filterNot { it in tolerated }
        val stale = tolerated.keys.filterNot { it in failures }
        if (unexpected.isEmpty() && stale.isEmpty()) return
        val report = buildString {
            if (unexpected.isNotEmpty()) {
                appendLine("${unexpected.size} reference(s) do not link against the baseline server API:")
                unexpected.forEach { appendLine("  $it") }
            }
            if (stale.isNotEmpty()) {
                appendLine("Allow-listed references no longer present; remove them:")
                stale.forEach { appendLine("  $it") }
            }
        }
        logger.error(report)
        throw GradleException("Server API linkage check failed; see the list above.")
    }

    private fun readClasses(root: File, sink: (String, ByteArray) -> Unit) {
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }
                .forEach { val data = it.readBytes(); sink(ClassReader(data).className, data) }
        } else if (root.isFile) {
            ZipFile(root).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") && it.name != "module-info.class" }
                    .forEach { e -> zip.getInputStream(e).use { sink(e.name.removeSuffix(".class"), it.readBytes()) } }
            }
        }
    }

    private class Info(val superName: String?, val interfaces: List<String>, val fields: Set<String>, val methods: Set<String>)

    private class ClassPool {
        private val bytes = HashMap<String, ByteArray>()
        private val infos = HashMap<String, Info?>()

        fun add(name: String, data: ByteArray) {
            bytes.putIfAbsent(name, data)
        }

        fun addArchive(file: File) {
            if (!file.isFile || !file.name.endsWith(".jar")) return
            ZipFile(file).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                    .forEach { e -> zip.getInputStream(e).use { add(e.name.removeSuffix(".class"), it.readBytes()) } }
            }
        }

        fun info(name: String): Info? = infos.getOrPut(name) {
            val data = bytes[name]
                ?: ClassLoader.getPlatformClassLoader().getResourceAsStream("$name.class")?.use { it.readBytes() }
                ?: return@getOrPut null
            val fields = mutableSetOf<String>()
            val methods = mutableSetOf<String>()
            var superName: String? = null
            var interfaces = emptyList<String>()
            ClassReader(data).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, n: String, signature: String?, s: String?, i: Array<out String>?) {
                    superName = s
                    interfaces = i.orEmpty().toList()
                }

                override fun visitField(access: Int, n: String, d: String, signature: String?, value: Any?) = null.also { fields += n + d }
                override fun visitMethod(access: Int, n: String, d: String, signature: String?, e: Array<out String>?) = null.also { methods += n + d }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
            Info(superName, interfaces, fields, methods)
        }

        /** JVMS 5.4.3.3/5.4.3.4: the class, its superclasses, then every superinterface. */
        fun resolves(owner: String, name: String, descriptor: String, field: Boolean): Boolean {
            val key = name + descriptor
            val seen = HashSet<String>()
            val queue = ArrayDeque(listOf(owner))
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (!seen.add(current)) continue
                val info = info(current) ?: continue
                if (key in (if (field) info.fields else info.methods)) return true
                info.superName?.let(queue::addLast)
                queue.addAll(info.interfaces)
            }
            // Interface owners inherit java/lang/Object's members.
            return !field && info("java/lang/Object")!!.methods.contains(key)
        }
    }
}
