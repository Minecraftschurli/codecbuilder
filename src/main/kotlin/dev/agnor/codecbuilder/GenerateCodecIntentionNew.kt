package dev.agnor.codecbuilder

import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInspection.isInheritorOf
import com.intellij.lang.jvm.JvmModifier
import com.intellij.lang.jvm.types.JvmPrimitiveTypeKind
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.parentOfType
import com.intellij.util.IncorrectOperationException
import java.util.*

private const val UNKNOWN_CODEC = "UNKNOWN_CODEC"
private const val UNKNOWN_PRIMITIVE_CODEC = "UNKNOWN_PRIMITIVE_CODEC"
private const val UNKNOWN_CLASS_CODEC = "UNKNOWN_CLASS_CODEC"
private const val MISSING_PRIMITIVE_STREAM_CODEC = "MISSING_PRIMITIVE_STREAM_CODEC"
private const val MULTI_DIMENSIONAL_ARRAY_CODEC = "MULTI_DIMENSIONAL_ARRAY_CODEC"
private const val MISSING_GETTER = "MISSING_GETTER"
private val ERRORED_CODECS = setOf(
    UNKNOWN_CODEC,
    UNKNOWN_PRIMITIVE_CODEC,
    UNKNOWN_CLASS_CODEC,
    MISSING_PRIMITIVE_STREAM_CODEC,
    MULTI_DIMENSIONAL_ARRAY_CODEC,
    MISSING_GETTER
)

class GenerateCodecIntentionNew : PsiElementBaseIntentionAction() {
    private class CodecSource(val fqn: String, val preferred: Set<String>)

    private class Member(
        val codec: String,
        val name: String,
        val optional: Boolean,
        val default: String?,
        val getter: String
    ) {
        override fun toString(): String {
            return "$codec.${if (optional || default != null) "optionalFieldOf" else "fieldOf"}(\"$name\"${if (default != null) ", $default" else ""}).forGetter($getter)"
        }
    }
    
    override fun getFamilyName() = "CodecBuilder"

    override fun getText() = "Generate Codec for type (insert)"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        element.parentOfType<PsiClass>(true) ?: return false
        return true
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val clazz = element.parentOfType<PsiClass>(true)!!
        val field = generateForClass(project, clazz)
        val success = field.initializer?.text?.let { text -> ERRORED_CODECS.none { text.contains(it) } } ?: false
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("CodecConstructionComplete")
        if (success) {
            notificationGroup.createNotification("Successfully generated codec", NotificationType.INFORMATION).notify(project)
        } else {
            notificationGroup.createNotification("Generated codec with some issues", NotificationType.WARNING).notify(project)
        }
        WriteCommandAction.writeCommandAction(project, clazz.containingFile).run<RuntimeException> {
            CodeStyleManager.getInstance(project).reformat(field)
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(field.initializer!!)
            GenerateMembersUtil.insert(clazz, field, clazz.lBrace, false)
        }
    }

    private fun getRegistrySources(project: Project): List<String> {
        return listOf(
            "net.minecraft.core.registries.BuiltInRegistries",
            "net.neoforged.neoforge.registries.NeoForgeRegistries"
        )
    }

    private fun getCodecSources(project: Project, clazz: PsiClass): List<CodecSource> {
        val sources = mutableListOf<CodecSource>()
        sources.add(CodecSource(clazz.qualifiedName!!, setOf("CODEC")))
        sources.add(CodecSource("com.mojang.serialization.Codec", setOf()))
        sources.add(CodecSource("net.minecraft.util.ExtraCodecs", setOf("JSON", "QUATERNIONF")))
        return sources
    }

    private fun generateForClass(project: Project, clazz: PsiClass): PsiField {
        val constructors = clazz.constructors
        @Suppress("UnstableApiUsage")
        val staticConstructors = clazz.methods.filter {
            it.hasModifier(JvmModifier.STATIC) && PsiTypesUtil.getPsiClass(it.returnType) == clazz
        }
        val allConstructors = (constructors + staticConstructors).sortedByDescending { it.parameterList.parametersCount }
        val getters = clazz.allMethods.filter {
            it.returnType != PsiPrimitiveType.VOID && it.parameterList.isEmpty
        }.sortedBy {
            it.name.startsWith("get") && !it.isDeprecated
        }
        val constructorMembers = allConstructors.map { toMembers(project, it, clazz, getters) }
        val candidates = mutableListOf<List<Member>>()
        if (clazz.isRecord) {
            candidates.add(clazz.recordComponents.map { it.toMember(project, clazz) })
        }
        candidates.addAll(constructorMembers)
        val candidate = candidates.minByOrNull { candidate ->
            candidate.sumOf { member -> ERRORED_CODECS.count { member.codec.contains(it) } + (if (member.getter == MISSING_GETTER) 1 else 0) } / candidate.size
        } ?: throw IncorrectOperationException()
        if (candidate.isEmpty()) {
            return generateUnitCodec(project, clazz)
        }
        if (candidate.size <= 16) {
            return generateRCBCodec(project, clazz, candidate)
        }
        throw IncorrectOperationException()
    }

    private fun toMembers(project: Project, constructor: PsiMethod, clazz: PsiClass, getters: List<PsiMethod>): List<Member> {
        return constructor.parameterList.parameters.map { parameter ->
            parameter.toMember(project, getters.find { it.returnType == parameter.type && (it.name == parameter.name || it.name == "get${parameter.name.capitalize()}") }, clazz)
        }
    }

    private fun generateUnitCodec(project: Project, clazz: PsiClass): PsiField {
        return createCodec(project, clazz, "com.mojang.serialization.Codec.unit(${clazz.name}::new)")
    }

    private fun generateRCBCodec(
        project: Project,
        clazz: PsiClass,
        members: List<Member>
    ): PsiField {
        val expression = members.joinToString(
            ",",
            "com.mojang.serialization.codecs.RecordCodecBuilder.create(inst -> inst.group(",
            ").apply(inst, ${clazz.qualifiedName}::new))"
        )
        return createCodec(project, clazz, expression)
    }

    private fun createCodec(
        project: Project,
        clazz: PsiClass,
        codecExpression: String
    ): PsiField {
        val javaPsiFacade = JavaPsiFacade.getInstance(project)
        val elementFactory = javaPsiFacade.elementFactory

        val allScope = GlobalSearchScope.allScope(project)
        val codecClazz = javaPsiFacade.findClass("com.mojang.serialization.Codec", allScope)!!

        val clazzType = elementFactory.createType(clazz)
        val codecType = elementFactory.createType(codecClazz, PsiSubstitutor.EMPTY.putAll(codecClazz, arrayOf(clazzType)))

        val field = elementFactory.createField("CODEC", codecType)
        PsiUtil.setModifierProperty(field, PsiModifier.PUBLIC, true)
        PsiUtil.setModifierProperty(field, PsiModifier.STATIC, true)
        PsiUtil.setModifierProperty(field, PsiModifier.FINAL, true)
        val initializer = elementFactory.createExpressionFromText(codecExpression, field)
        field.initializer = initializer

        return field
    }

    private fun getCodec(project: Project, type: PsiType, targetClass: PsiClass): String {
        val codec = when (type) {
            is PsiPrimitiveType -> getPrimitiveCodec(type)
            is PsiClassType -> getObjectCodec(project, type, targetClass)
            is PsiArrayType -> getArrayCodec(project, type, targetClass)
            else -> UNKNOWN_CODEC
        }
        return codec
    }

    @Suppress("UnstableApiUsage")
    private fun getPrimitiveCodec(type: PsiPrimitiveType) = getPrimitiveCodec(type.kind)

    @Suppress("UnstableApiUsage")
    private fun getPrimitiveCodec(kind: JvmPrimitiveTypeKind) = when (kind) {
        JvmPrimitiveTypeKind.BOOLEAN -> "com.mojang.serialization.Codec.BOOL"
        JvmPrimitiveTypeKind.BYTE -> "com.mojang.serialization.Codec.BYTE"
        JvmPrimitiveTypeKind.SHORT -> "com.mojang.serialization.Codec.SHORT"
        JvmPrimitiveTypeKind.INT -> "com.mojang.serialization.Codec.INT"
        JvmPrimitiveTypeKind.LONG -> "com.mojang.serialization.Codec.LONG"
        JvmPrimitiveTypeKind.FLOAT -> "com.mojang.serialization.Codec.FLOAT"
        JvmPrimitiveTypeKind.DOUBLE -> "com.mojang.serialization.Codec.DOUBLE"
        JvmPrimitiveTypeKind.CHAR -> "com.mojang.serialization.Codec.STRING.comapFlatMap(s -> s.length() != 1 ? com.mojang.serialization.DataResult.error(() -> \"'\" + s + \"' is an invalid symbol (must be 1 character only).\") : com.mojang.serialization.DataResult.success(s.charAt(0)), String::valueOf)"
        else -> UNKNOWN_PRIMITIVE_CODEC
    }

    private fun getObjectCodec(project: Project, type: PsiClassType, targetClass: PsiClass): String {
        val clazz = PsiUtil.resolveClassInClassTypeOnly(type)
        when (clazz?.qualifiedName) {
            null -> return UNKNOWN_CLASS_CODEC
            "java.lang.String" -> return "com.mojang.serialization.Codec.STRING"
            "java.util.Optional" -> return getCodec(project, type.parameters.first(), targetClass) + "§"
            "java.util.OptionalInt" -> return getPrimitiveCodec(PsiType.INT) + "§"
            "java.util.OptionalLong" -> return getPrimitiveCodec(PsiType.LONG) + "§"
            "java.util.OptionalDouble" -> return getPrimitiveCodec(PsiType.DOUBLE) + "§"
            "com.mojang.datafixers.util.Pair" -> return getPairCodec(type, project, targetClass)
            "com.mojang.datafixers.util.Either" -> return getEitherCodec(type, project, targetClass)
            "java.util.List" -> {
                val listType = type.parameters.first()
                if (listType is PsiClassType) {
                    val inner = PsiUtil.resolveClassInClassTypeOnly(listType)
                    if (inner?.qualifiedName == "com.mojang.datafixers.util.Pair") {
                        val (first, second) = listType.parameters
                        return "com.mojang.serialization.Codec.compoundList(${getCodec(project, first, targetClass)}, ${getCodec(project, second, targetClass)})"
                    }
                }
                return "${getCodec(project, listType, targetClass)}.listOf()"
            }
            "java.util.Map" -> return getMapCodec(type, project, targetClass)
            "java.util.Set" -> return getSetCodec(type, project, targetClass)
            else -> {
                @Suppress("UnstableApiUsage")
                val primitive = JvmPrimitiveTypeKind.getKindByFqn(clazz.qualifiedName)
                if (primitive != null) return getPrimitiveCodec(primitive)

                val scope = GlobalSearchScope.allScope(project)
                val javaPsiFacade = JavaPsiFacade.getInstance(project)
                
                if (clazz.isEnum) {
                    return getEnumCodec(type, clazz, targetClass)
                }

                val isHolder = clazz.qualifiedName == "net.minecraft.core.Holder"
                val registryCodec = getRegistryCodec(project, targetClass, if (isHolder) type.parameters.first() else type, isHolder)
                if (registryCodec != null) return registryCodec

                val codecSources = getCodecSources(project, clazz)
                for (codecSource in codecSources) {
                    val from = javaPsiFacade.findClass(codecSource.fqn, scope) ?: continue
                    val codecs = getStaticCodecs(from, targetClass, type).sortedBy { it.name in codecSource.preferred }
                    if (codecs.isEmpty()) continue
                    return "${from.qualifiedName}.${codecs.first().name}"
                }

                return UNKNOWN_CLASS_CODEC
            }
        }
    }

    private fun getPairCodec(
        type: PsiClassType,
        project: Project,
        targetClass: PsiClass
    ): String {
        val (first, second) = type.parameters
        val firstCodec = getCodec(project, first, targetClass)
        val secondCodec = getCodec(project, second, targetClass)
        return "com.mojang.serialization.Codec.pair($firstCodec, $secondCodec)"
    }

    private fun getEitherCodec(
        type: PsiClassType,
        project: Project,
        targetClass: PsiClass
    ): String {
        val (first, second) = type.parameters
        val firstCodec = getCodec(project, first, targetClass)
        val secondCodec = getCodec(project, second, targetClass)
        return "com.mojang.serialization.Codec.either($firstCodec, $secondCodec)"
    }

    private fun getMapCodec(
        type: PsiClassType,
        project: Project,
        targetClass: PsiClass
    ): String {
        val (first, second) = type.parameters
        val firstCodec = getCodec(project, first, targetClass)
        val secondCodec = getCodec(project, second, targetClass)
        return "com.mojang.serialization.Codec.unboundedMap($firstCodec, $secondCodec)"
    }

    private fun getSetCodec(
        type: PsiClassType,
        project: Project,
        targetClass: PsiClass
    ): String {
        val setType = type.parameters.first()
        return "${getCodec(project, setType, targetClass)}.listOf().xmap(java.util.Set::copyOf, java.util.List::copyOf)"
    }

    private fun getEnumCodec(type: PsiClassType, clazz: PsiClass, targetClass: PsiClass): String {
        if (type.isInheritorOf("net.minecraft.util.StringRepresentable")) {
            val codec = getStaticCodecs(clazz, targetClass, type).firstOrNull()
            if (codec != null) return "${clazz.name}.${codec.name}"
            return "net.minecraft.util.StringRepresentable.fromEnum(${clazz.qualifiedName}::values)"
        }
        return "net.minecraft.util.ExtraCodecs.orCompressed(net.minecraft.util.ExtraCodecs.stringResolverCodec(${clazz.qualifiedName}::name, ${clazz.qualifiedName}::valueOf), net.minecraft.util.ExtraCodecs.idResolverCodec(${clazz.qualifiedName}::ordinal, i -> i >= 0 && i < ${clazz.qualifiedName}.values().length ? ${clazz.qualifiedName}.values()[i] : null, -1))"
    }

    @Suppress("UnstableApiUsage")
    private fun getArrayCodec(project: Project, type: PsiArrayType, targetClass: PsiClass): String {
        when (val componentType = type.componentType) {
            is PsiPrimitiveType -> {
                if (componentType.kind !in setOf(JvmPrimitiveTypeKind.DOUBLE, JvmPrimitiveTypeKind.INT, JvmPrimitiveTypeKind.LONG)) {
                    return MISSING_PRIMITIVE_STREAM_CODEC
                }
                return "${getCodec(project, componentType, targetClass)}.listOf().xmap(list -> list.stream().mapTo${componentType.name.capitalize()}(val -> val).toArray(), arr -> java.util.Arrays.stream(arr).boxed().toList())"
            }
            is PsiClassType -> return "${getCodec(project, componentType, targetClass)}.listOf().xmap(list -> list.toArray(new ${componentType.name}[0]), arr -> java.util.Arrays.stream(arr).toList())"
            else -> return MULTI_DIMENSIONAL_ARRAY_CODEC
        }
    }

    @Suppress("UnstableApiUsage")
    private fun getRegistryCodec(project: Project, targetClass: PsiClass, type: PsiType, wrapped: Boolean): String? {
        val scope = GlobalSearchScope.allScope(project)
        val facade = JavaPsiFacade.getInstance(project)

        val registrySources = getRegistrySources(project)
        val registry = registrySources
            .map { facade.findClass(it, scope) }
            .associateWith { sourceClass -> sourceClass?.fields?.firstOrNull {
                it.hasModifier(JvmModifier.STATIC) && PsiUtil.isMemberAccessibleAt(
                    it,
                    targetClass
                ) && isRegistryOf(it.type, type)
            }}
            .entries
            .firstOrNull { (clazz, field) -> clazz != null && field != null }
        if (registry != null) {
            val (clazz, field) = registry
            return "${clazz!!.qualifiedName}.${field!!.name}.${if (wrapped) "holderByNameCodec" else "byNameCodec"}()"
        }
        return null
    }

    @Suppress("UnstableApiUsage")
    private fun getStaticCodecs(
        clazz: PsiClass,
        targetClass: PsiClass,
        type: PsiClassType
    ) = clazz.fields.filter {
        it.hasModifier(JvmModifier.STATIC) && PsiUtil.isMemberAccessibleAt(it, targetClass) && isCodecOf(it.type, type)
    }

    private fun isCodecOf(type: PsiType, type1: PsiClassType): Boolean {
        if (type !is PsiClassType) return false
        if (PsiUtil.resolveClassInClassTypeOnly(type)?.qualifiedName != "com.mojang.serialization.Codec") return false
        val codecType = type.parameters.firstOrNull() ?: return false
        return codecType == type1
    }

    private fun isRegistryOf(type: PsiType, type1: PsiType): Boolean {
        if (type !is PsiClassType) return false
        if (!type.isInheritorOf("net.minecraft.core.Registry")) return false
        val regType = type.parameters.firstOrNull() ?: return false
        return regType == type1
    }

    private fun PsiRecordComponent.toMember(project: Project, clazz: PsiClass): Member {
        val getter = "${clazz.qualifiedName}::$name"
        val codec = getCodec(project, type, clazz)
        val optional = codec.endsWith('§')
        if (codec.indexOf('§') != codec.lastIndexOf('§')) {
            throw IncorrectOperationException("Optional in unexpected place")
        }
        val default: String? = null
        return Member(codec.replace("§", ""), name!!, optional, default, getter)
    }

    private fun PsiParameter.toMember(project: Project, getter: PsiMethod?, clazz: PsiClass): Member {
        val codec = getCodec(project, type, clazz)
        val optional = codec.endsWith('§')
        if (codec.indexOf('§') != codec.lastIndexOf('§')) {
            throw IncorrectOperationException("Optional in unexpected place")
        }
        val default: String? = null
        return Member(codec.replace("§", ""), name, optional, default, getter?.let { "${clazz.qualifiedName}::${it.name}" } ?: MISSING_GETTER)
    }

    private fun String.capitalize(): String = this.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
}
