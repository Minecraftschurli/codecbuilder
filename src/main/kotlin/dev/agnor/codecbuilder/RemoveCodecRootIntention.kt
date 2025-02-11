package dev.agnor.codecbuilder

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

class RemoveCodecRootIntention : CodecRootIntention() {

    override fun getText(): String {
        return super.getText() + "Remove"
    }
    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val clazz = findClass(project, element) ?: return false
        return getStoredCodecRoots().contains(clazz.qualifiedName);
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val clazz = findClass(project, element)?.qualifiedName ?: return
        removeStoredCodecRoots(clazz);
    }
}