package dev.agnor.codecbuilder

import com.intellij.lang.jvm.JvmModifier
import com.intellij.lang.jvm.JvmModifiersOwner
import com.intellij.lang.jvm.types.JvmPrimitiveTypeKind
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.PsiImmediateClassType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.childrenOfType
import com.intellij.psi.util.parentOfType
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

open class GenerateCodecIntention : CodecRootIntention() {

    override fun getText() = "Generate Codec for type"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        return findClass(project, element) != null
    }


    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val codecClass = findClass(project, element) ?: return
        val file = element.parentOfType<PsiJavaFile>();
        val fileClazz :PsiClass?
        if (file == null) {
            println("null")
            return
        } else {
            if (!file.name.endsWith(".java"))
                return
            fileClazz = file.childrenOfType<PsiClass>()
                    .firstOrNull{ cls -> cls.name == file.name.substringBeforeLast(".java")}
        }
        fileClazz?: return
        val imports = HashSet<PsiClass>()
        getCodecRoot(project, DefaultSources.CODEC)?.let { imports.add(it) }
        JavaPsiFacade.getInstance(project).findClass("com.mojang.serialization.codecs.RecordCodecBuilder", GlobalSearchScope.allScope(project))?.let { imports.add(it) }
        val members = getMembers(codecClass, fileClazz, project, imports)
        var str = "public static final Codec<" + codecClass.name + "> CODEC = RecordCodecBuilder.create(instance -> instance.group(\n"
        for (member in members) {
            val separator = if (members[members.size - 1] == member) "" else ","
            val fieldOf = if (member.codec.endsWith(".optional", false)) "FieldOf" else ".fieldOf"
            str += member.codec + "$fieldOf(\"" + member.name + "\").forGetter(" + member.methodName + ")" + separator + "\n"
        }
        str += ").apply(instance, " + codecClass.name + "::new));";
        val selection = java.awt.datatransfer.StringSelection(str);
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
        NotificationGroupManager.getInstance().getNotificationGroup("CodecConstructionComplete").createNotification("Codec copied to clipboard", NotificationType.INFORMATION).notify(project);
        if (!shouldImport())
            return
        for (import in imports) {
            file.importClass(import)
        }
    }
    fun getMembers(codecClass: PsiClass, source: PsiClass, project: Project, imports: MutableSet<PsiClass>): List<Member> {
        if (codecClass.isRecord) {
            return codecClass.fields
                    .filter { field -> !field.hasModifier(JvmModifier.STATIC) }
                    .map { field -> Member(field.name, codecClass.name + "::" + field.name, getCodec(field.type, project, source, imports)) }
        }
        val isSingleConstructor = codecClass.constructors.size == 1
        constructor@ for (method in codecClass.constructors) {
            val members = ArrayList<Member>();
            val constructorImports = HashSet<PsiClass>();
            for (parameter in method.parameterList.parameters) {
                val name = parameter.name
                var memberGetter: String? = null
                val parameterType = parameter.type
                for (getter in codecClass.findMethodsByName(name)) {
                    val returnType = getter.returnType ?: continue
                    if (parameterType == returnType && getter.parameters.isEmpty() && !getter.hasModifier(JvmModifier.STATIC) && getter.hasModifier(JvmModifier.PUBLIC)) {
                        memberGetter = codecClass.name + "::" + getter.name
                        break;
                    }
                }
                val prefix = if (parameterType is PsiPrimitiveType && parameterType.kind == JvmPrimitiveTypeKind.BOOLEAN) "is" else "get"
                val methodName = camelCase(name, prefix)
                for (getter in codecClass.findMethodsByName(methodName)) {
                    val returnType = getter.returnType ?: continue
                    if (parameterType == returnType && getter.parameters.isEmpty() && !getter.hasModifier(JvmModifier.STATIC) && getter.hasModifier(JvmModifier.PUBLIC)) {
                        memberGetter = codecClass.name + "::" + getter.name
                        break
                    }
                }
                if (memberGetter == null) {
                    if (isSingleConstructor) {
                        memberGetter = codecClass.name + "::missingGetter";
                    } else {
                        continue@constructor
                    }
                }
                val codec = getCodec(parameterType, project, codecClass, constructorImports)
                if (codec.contains("MissingCodec") && !isSingleConstructor)
                    continue@constructor
                members.add(Member(name, memberGetter, codec))
            }
            imports.addAll(constructorImports)
            return members
        }
        return ArrayList()
    }
    fun camelCase(text: String, preFix: String): String {
        if (text.isEmpty())
            return preFix;
        return preFix + text[0].uppercaseChar() + text.substring(1)
    }
    fun getCodec(type: PsiType, project: Project, originCodecRoot: PsiClass, imports: MutableSet<PsiClass>): String {
        when (type) {
            is PsiPrimitiveType -> {
                return getCodecForPrimitive(type, project, imports)
            }
            is PsiClassType -> {
                return getCodecForClass(type, project, originCodecRoot, imports)
            }
            is PsiArrayType -> {
                return getCodecForArray(type, project, originCodecRoot, imports)
            }
        }
        return "MissingMainTypeCodec, pls report to author with context: " + type.javaClass.canonicalName
    }

    fun getCodecForPrimitive(type: PsiPrimitiveType, project: Project, imports: MutableSet<PsiClass>): String {
        getCodecRoot(project, DefaultSources.CODEC)?.let { imports.add(it) }
        if (type.name == "boolean")
            return "Codec.BOOL"
        return "Codec." + type.name.uppercase(Locale.ROOT)
    }
    fun getCodecForClass(type: PsiClassType, project: Project, originCodecRoot: PsiClass, imports: MutableSet<PsiClass>): String {
        if (type.canonicalText.contains("java.util.List", false)) {
            return getCodec(type.parameters[0], project, originCodecRoot, imports) + ".listOf()"
        }
        if (type.canonicalText.contains("java.util.Optional", false)) {
            return getCodec(type.parameters[0], project, originCodecRoot, imports) + ".optional" //marker for later resolution
        }
        if (type.canonicalText.contains("com.mojang.datafixers.util.Either", false)) {
            val codecA = getCodec(type.parameters[0], project, originCodecRoot, imports)
            val codecB = getCodec(type.parameters[1], project, originCodecRoot, imports)
            getCodecRoot(project, DefaultSources.EXTRACODECS)?.let { imports.add(it) }
            return "ExtraCodecs.either($codecA, $codecB)"
        }
        if (type.canonicalText.contains("java.util.Map", false)) {
            val codecKey = getCodec(type.parameters[0], project, originCodecRoot, imports)
            val codecValue = getCodec(type.parameters[1], project, originCodecRoot, imports)
            getCodecRoot(project, DefaultSources.CODEC)?.let { imports.add(it) }
            return "Codec.unboundedMap($codecKey, $codecValue)"
        }
        val field = type.resolve()?.findFieldByName("CODEC", false) //don't search base classes
        if (field != null && hasValidAccess(field)) {
            val fieldType = field.type
            if (fieldType is PsiClassType
                    && fieldType.canonicalText.contains("com.mojang.serialization.Codec", false)
                    && fieldType.parameters[0] == (type)) {
                return type.name + ".CODEC"
            }
        }
        if (getGenericType(type, "java.lang.Enum") != null) {
            val stringClass = JavaPsiFacade.getInstance(project).findClass("java.lang.String", GlobalSearchScope.allScope(project))!!
            val stringType = PsiImmediateClassType(stringClass, PsiSubstitutor.UNKNOWN)
            JavaPsiFacade.getInstance(project).findClass("java.util.Objects", GlobalSearchScope.allScope(project))?.let { imports.add(it) }
            return getCodec(stringType, project, originCodecRoot, imports) + ".xmap(str -> Objects.requireNonNull(" + type.name + ".valueOf(str)), " + type.name + "::name)"
        }
        var codecForClass = getCodecForClass(type, originCodecRoot, imports)
        if (codecForClass != null)
            return codecForClass;

        for (codecRoot in getCodecRoots(project)) {
            codecForClass = getCodecForClass(type, codecRoot, imports)
            if (codecForClass != null)
                return codecForClass;
        }
        val registryCodec = getRegistriesCodecs(project)[type]
        if (registryCodec != null) {
            imports.add(registryCodec.source)
            return registryCodec.codecString + ".byNameCodec()"
        }
        if (type.canonicalText.contains("net.minecraft.core.Holder", false)) {
            val codec = getRegistriesCodecs(project)[type.parameters[0]]
            if (codec != null) {
                imports.add(codec.source)
                return codec.codecString + ".holderByNameCodec()"
            }
        }
        return "MissingCodec";
    }

    fun getCodecForArray(type: PsiArrayType, project: Project, originCodecRoot: PsiClass, imports: MutableSet<PsiClass>): String {
        val arrType = type.componentType
        val codec = getCodec(arrType, project, originCodecRoot, imports) + ".listOf().xmap(";
        if (arrType is PsiPrimitiveType) {
            val hasPrimitiveStream = arrType.kind == JvmPrimitiveTypeKind.DOUBLE || arrType.kind == JvmPrimitiveTypeKind.INT || arrType.kind == JvmPrimitiveTypeKind.LONG
            val arrTypeName = if (hasPrimitiveStream) arrType.name else "MissingPrimitiveStream"
            JavaPsiFacade.getInstance(project).findClass("java.util.Arrays", GlobalSearchScope.allScope(project))?.let { imports.add(it) }
            return codec + "list -> list.stream().mapTo" + camelCase(arrTypeName, "") + "(val -> val).toArray(), arr -> Arrays.stream(arr).boxed().toList())"
        } else if (arrType is PsiClassType) {
            JavaPsiFacade.getInstance(project).findClass("java.util.Arrays", GlobalSearchScope.allScope(project))?.let { imports.add(it) }
            return codec + "list -> list.toArray(new " + arrType.name + "[0]), arr -> Arrays.stream(arr).toList())"
        }
        return "OnlySingleDimensionArrays"
    }

    fun hasValidAccess(field: JvmModifiersOwner): Boolean {
        return field.hasModifier(JvmModifier.STATIC) && field.hasModifier(JvmModifier.PUBLIC);
    }

    fun getCodecForClass(target: PsiClassType, codecSource: PsiClass, imports: MutableSet<PsiClass>): String? {
        for (field in codecSource.allFields) {
            if (!codecSource.isInterface && (!field.hasModifier(JvmModifier.PUBLIC) || !field.hasModifier(JvmModifier.STATIC)))
                continue;
            val codecType = getCodecType(field.type)?: continue
            if (codecType == target) {
                imports.add(codecSource)
                return codecSource.name + "." + field.name
            }
        }
        return null;
    }

    fun getCodecType(type: PsiType): PsiType? {
        return getGenericType(type, "com.mojang.serialization.Codec")
    }

    fun getGenericType(type: PsiType, targetType: String): PsiType? {
        if (type is PsiClassType && type.canonicalText.contains(targetType, false)) {
            return type.parameters[0]
        }
        for (superType in type.superTypes) {
            val codecType = getGenericType(superType, targetType);
            if (codecType != null)
                return codecType
        }
        return null
    }
    fun getRegistriesCodecs(project: Project) : Map<PsiType, RegistryCodecValue> {
        if (registries == null) {
            val tempMap = HashMap<PsiType, RegistryCodecValue>()
            for (root in getCodecRoots(project)) {
                for (field in root.allFields) {
                    val registryType = getRegistryType(field.type) ?: continue
                    tempMap[registryType] = RegistryCodecValue(root.name + "." + field.name, root)
                }
            }
            registries = tempMap
        }
        return registries as Map<PsiType, RegistryCodecValue>
    }
    var registries: MutableMap<PsiType, RegistryCodecValue>? = null;
    fun getRegistryType(type: PsiType): PsiType? {
        return getGenericType(type, "net.minecraft.core.Registry")
    }

    open fun shouldImport() = false
}