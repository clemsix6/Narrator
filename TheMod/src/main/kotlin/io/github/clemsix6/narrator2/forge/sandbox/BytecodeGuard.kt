package io.github.clemsix6.narrator2.forge.sandbox

import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.ForgeLimits
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.TypePath

/**
 * The allow-list guard on a module's bytecode (M5 §3.3, §5.4 step 3–4): before anything is loaded, every type the
 * module names, every member it calls (descriptor included), every dynamic call, constant, `catch`, supertype,
 * annotation and method flag must be on the list ([SandboxPolicy]); anything else is a [Violation], with a reason
 * written for the forger. The guard runs on the compiler thread, once per compilation, never on the server thread.
 *
 * "A module's own classes" is the set of names its compilation produced ([own]), computed before the rewriting ever
 * adds its holder class — never a prefix of a name, so a fabricated class cannot pass itself off as the module's by
 * being called `Top$...` (M5 design review, finding 7). The guard also enforces the module's shape: exactly one
 * top-level class, the others nested under it, none carrying the reserved meter marker, within the size limits.
 */
class BytecodeGuard {
    /**
     * A refusal: [where] the class (and member) it was found in, [line] in the source when known (else 0), [what] the
     * offending symbol, [reason] why it is refused. The compiler's diagnostics (M5-02) render it as a `refused` line.
     */
    data class Violation(val where: String, val line: Int, val what: String, val reason: String) {
        override fun toString(): String = where + (if (line > 0) ":$line" else "") + ": " + what + " -> " + reason
    }

    /** Checks every class of a module (binary name → bytecode) against the allow-list; the list is empty when it is clean. */
    fun check(classes: Map<String, ByteArray>): List<Violation> {
        val bytes = classes.mapKeys { it.key.replace('.', '/') }
        val violations = mutableListOf<Violation>()
        structure(bytes, violations)
        val own = bytes.keys.toSet()
        for ((name, body) in bytes) ClassReader(body).accept(Checker(name, own, violations), ClassReader.SKIP_FRAMES)
        return violations
    }

    /** The module's shape: one top-level class, the rest nested under it, no reserved marker, within the limits (M5 §5.4 step 3). */
    private fun structure(classes: Map<String, ByteArray>, violations: MutableList<Violation>) {
        val names = classes.keys
        names.filter { it.endsWith(Built.METER_SUFFIX) || "\$-" in it }
            .forEach { violations += Violation(it.replace('/', '.'), 0, "class $it", "a module cannot define a class with the reserved meter marker") }
        if (names.size > ForgeLimits.MAX_CLASSES) {
            violations += Violation("<module>", 0, "${names.size} classes", "a module has at most ${ForgeLimits.MAX_CLASSES} classes")
        }
        val total = classes.values.sumOf { it.size }
        if (total > ForgeLimits.MAX_BYTECODE) {
            violations += Violation("<module>", 0, "$total bytes of bytecode", "a module has at most ${ForgeLimits.MAX_BYTECODE} bytes of bytecode")
        }
        val tops = names.filter { '$' !in it }
        if (tops.size != 1) {
            violations += Violation("<module>", 0, "top-level classes ${tops.map { it.replace('/', '.') }}", "a module has exactly one top-level class")
            return
        }
        val top = tops.single()
        names.filter { it != top && !it.startsWith("$top\$") }
            .forEach { violations += Violation(it.replace('/', '.'), 0, "class $it", "a module defines only its own class and classes nested in it") }
        SandboxPolicy.shadowRefusal(top.substringAfterLast('/'))?.let { violations += Violation(top.replace('/', '.'), 0, "class $top", it) }
    }

    private inner class Checker(
        private val self: String,
        private val own: Set<String>,
        private val violations: MutableList<Violation>,
    ) : ClassVisitor(Opcodes.ASM9) {
        private val where = self.replace('/', '.')

        private fun flag(line: Int, what: String, reason: String?) {
            if (reason != null) violations += Violation(where, line, what, reason)
        }

        /** The type a descriptor or internal name names, unless it is one of the module's own classes. */
        private fun checkType(descriptorOrName: String, line: Int) {
            var internal = descriptorOrName.trimStart('[')
            if (internal.startsWith("L") && internal.endsWith(";")) internal = internal.substring(1, internal.length - 1)
            if (internal.length <= 1) return // a primitive
            if (internal in own) return
            flag(line, internal.replace('/', '.'), SandboxPolicy.typeRefusal(internal))
        }

        private fun checkDescriptor(descriptor: String, line: Int) {
            val type = Type.getType(descriptor)
            if (type.sort == Type.METHOD) {
                type.argumentTypes.forEach { checkType(it.descriptor, line) }
                checkType(type.returnType.descriptor, line)
            } else {
                checkType(descriptor, line)
            }
        }

        private fun checkMember(owner: String, name: String, descriptor: String, line: Int) {
            // A forbidden name (wait, notify, finalize, printStackTrace…) is refused on every owner, the module's own included.
            flag(line, "${owner.replace('/', '.')}.$name", SandboxPolicy.forbiddenMemberName(name))
            if (owner !in own) {
                checkType(owner, line)
                flag(line, "${owner.replace('/', '.')}.$name", SandboxPolicy.restrictedMemberRefusal(owner, name))
            }
            checkDescriptor(descriptor, line)
        }

        private fun checkConstant(value: Any?, line: Int) {
            when (value) {
                is Handle -> checkMember(value.owner, value.name, value.desc, line)
                is Type -> if (value.sort == Type.METHOD) checkDescriptor(value.descriptor, line) else checkType(value.descriptor, line)
                is ConstantDynamic -> flag(line, "dynamic constant ${value.name}", "a module cannot use a dynamic constant")
            }
        }

        private fun annotation(descriptor: String, line: Int): AnnotationVisitor {
            flag(line, "annotation ${descriptor.removePrefix("L").removeSuffix(";").replace('/', '.')}", SandboxPolicy.annotationRefusal(descriptor))
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visitEnum(name: String?, enumDescriptor: String, value: String) = checkType(enumDescriptor, line)
                override fun visitAnnotation(name: String?, nested: String): AnnotationVisitor = annotation(nested, line)
                override fun visitArray(name: String?): AnnotationVisitor = this
            }
        }

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            if (superName != null && superName !in own) flag(0, "extends ${superName.replace('/', '.')}", SandboxPolicy.superclassRefusal(superName))
            interfaces?.forEach { face ->
                if (face in own) return@forEach
                val refusal = SandboxPolicy.typeRefusal(face)
                    ?: SandboxPolicy.containerRefusal(face)
                    ?: if (SandboxPolicy.isSdkType(face) && face != SandboxPolicy.FORGE_MODULE) "a module may implement only ForgeModule from the SDK" else null
                flag(0, "implements ${face.replace('/', '.')}", refusal)
            }
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor = annotation(descriptor, 0)

        override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String, visible: Boolean): AnnotationVisitor =
            annotation(descriptor, 0)

        override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor {
            checkType(descriptor, 0)
            return object : FieldVisitor(Opcodes.ASM9) {
                override fun visitAnnotation(fieldAnnotation: String, visible: Boolean): AnnotationVisitor = annotation(fieldAnnotation, 0)
                override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, typeAnnotation: String, visible: Boolean): AnnotationVisitor =
                    annotation(typeAnnotation, 0)
            }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
            if (access and Opcodes.ACC_NATIVE != 0) flag(0, "native $name", "a module cannot declare native methods")
            if (access and Opcodes.ACC_SYNCHRONIZED != 0) flag(0, "synchronized $name", "a module cannot use monitors")
            if (name == "finalize" && descriptor == "()V") flag(0, "finalize", "finalizers run on another thread")
            checkDescriptor(descriptor, 0)
            exceptions?.forEach { checkType(it, 0) }
            return object : MethodVisitor(Opcodes.ASM9) {
                private var line = 0

                override fun visitLineNumber(newLine: Int, start: Label) {
                    line = newLine
                }

                override fun visitAnnotation(methodAnnotation: String, visible: Boolean): AnnotationVisitor = annotation(methodAnnotation, line)
                override fun visitParameterAnnotation(parameter: Int, parameterAnnotation: String, visible: Boolean): AnnotationVisitor =
                    annotation(parameterAnnotation, line)
                override fun visitTypeAnnotation(typeRef: Int, typePath: TypePath?, typeAnnotation: String, visible: Boolean): AnnotationVisitor =
                    annotation(typeAnnotation, line)
                override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, insnAnnotation: String, visible: Boolean): AnnotationVisitor =
                    annotation(insnAnnotation, line)
                override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, catchAnnotation: String, visible: Boolean): AnnotationVisitor =
                    annotation(catchAnnotation, line)
                override fun visitLocalVariableAnnotation(
                    typeRef: Int, typePath: TypePath?, start: Array<out Label>, end: Array<out Label>, index: IntArray, localAnnotation: String, visible: Boolean,
                ): AnnotationVisitor = annotation(localAnnotation, line)

                override fun visitInsn(opcode: Int) {
                    if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) flag(line, "synchronized block", "a module cannot use monitors")
                }

                override fun visitTypeInsn(opcode: Int, type: String) = checkType(type, line)

                override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, fieldDescriptor: String) {
                    if (owner !in own) checkType(owner, line)
                    checkType(fieldDescriptor, line)
                }

                override fun visitMethodInsn(opcode: Int, owner: String, methodName: String, methodDescriptor: String, isInterface: Boolean) =
                    checkMember(owner, methodName, methodDescriptor, line)

                override fun visitInvokeDynamicInsn(callName: String, callDescriptor: String, bootstrap: Handle, vararg arguments: Any?) {
                    if (!SandboxPolicy.isBootstrap(bootstrap.owner, bootstrap.name)) {
                        flag(line, "invokedynamic ${bootstrap.owner.replace('/', '.')}.${bootstrap.name}", "a module cannot use this dynamic call")
                    }
                    checkDescriptor(callDescriptor, line)
                    arguments.forEach { checkConstant(it, line) }
                }

                override fun visitLdcInsn(value: Any?) {
                    if (value is Handle || value is ConstantDynamic) flag(line, "a method-handle or dynamic constant", "a module cannot load a method handle")
                    else checkConstant(value, line)
                }

                override fun visitMultiANewArrayInsn(descriptor: String, dimensions: Int) =
                    flag(line, "a multi-dimensional array", "a module uses one array dimension at a time")

                override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, caught: String?) {
                    if (caught == null) return // a finally block
                    flag(line, "catch ${caught.replace('/', '.')}", SandboxPolicy.catchRefusal(caught))
                }

                override fun visitLocalVariable(localName: String, localDescriptor: String, signature: String?, start: Label, end: Label, index: Int) =
                    checkType(localDescriptor, line)
            }
        }
    }
}
