package com.panomc.platform

import org.pf4j.ManifestPluginDescriptorFinder
import org.pf4j.PluginDescriptor
import java.util.jar.Manifest

class PanoManifestPluginDescriptorFinder : ManifestPluginDescriptorFinder() {
    companion object {
        private const val PLUGIN_ID: String = "id"
        private const val PLUGIN_NAME: String = "name"
        private const val PLUGIN_DESCRIPTION: String = "description"
        private const val PLUGIN_CLASS: String = "main-class"
        private const val PLUGIN_VERSION: String = "version"
        private const val PLUGIN_DEVELOPER: String = "developer"
        private const val PLUGIN_LICENSE: String = "license"
        private const val PLUGIN_SOURCE_URL: String = "source-url"
        private const val PLUGIN_DEPENDENCIES: String = "dependencies"
        private const val PLUGIN_REQUIRES: String = "requires"
    }

    override fun createPluginDescriptorInstance(): PanoPluginDescriptor {
        return PanoPluginDescriptor()
    }

    override fun createPluginDescriptor(manifest: Manifest): PluginDescriptor {
        val pluginDescriptor = createPluginDescriptorInstance()

        val attributes = manifest.mainAttributes
        val id = attributes.getValue(PLUGIN_ID)
        val name = attributes.getValue(PLUGIN_NAME)
        val description = attributes.getValue(PLUGIN_DESCRIPTION)
        val clazz = attributes.getValue(PLUGIN_CLASS)
        val version = attributes.getValue(PLUGIN_VERSION)
        val developer = attributes.getValue(PLUGIN_DEVELOPER)
        val license = attributes.getValue(PLUGIN_LICENSE)
        val sourceUrl = attributes.getValue(PLUGIN_SOURCE_URL)
        val dependencies = attributes.getValue(PLUGIN_DEPENDENCIES)
        val requires = attributes.getValue(PLUGIN_REQUIRES)

        pluginDescriptor.pluginId = id
        pluginDescriptor.name = name
        pluginDescriptor.description = description
        pluginDescriptor.setPluginClass(clazz)
        pluginDescriptor.setPluginVersion(version)
        pluginDescriptor.developer = developer
        pluginDescriptor.setLicense(license)
        pluginDescriptor.sourceUrl = sourceUrl
        pluginDescriptor.setDependencies(dependencies)
        pluginDescriptor.setRequires(requires)

        return pluginDescriptor
    }
}