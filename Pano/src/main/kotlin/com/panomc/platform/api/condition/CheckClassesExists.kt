package com.panomc.platform.api.condition

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.core.type.ClassMetadata

class CheckClassesExists : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        if (metadata is ClassMetadata) {
            val className = metadata.className
            try {
                val clazz = context.classLoader!!.loadClass(className)
                clazz.interfaces // check if any of them are not exists
                return true
            } catch (e: NoClassDefFoundError) {
                return false
            } catch (e: ClassNotFoundException) {
                return false
            }
        }

        return false
    }
}