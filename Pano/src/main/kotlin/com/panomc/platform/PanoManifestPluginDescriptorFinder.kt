package com.panomc.platform

import org.pf4j.ManifestPluginDescriptorFinder
import org.pf4j.PluginDescriptor
import org.pf4j.util.StringUtils
import java.util.jar.Manifest

class PanoManifestPluginDescriptorFinder : ManifestPluginDescriptorFinder() {
    companion object {
        private const val PLUGIN_SOURCE_URL: String = "Plugin-Source-Url"
    }

    override fun createPluginDescriptorInstance(): PanoPluginDescriptor {
        return PanoPluginDescriptor()
    }

    override fun createPluginDescriptor(manifest: Manifest): PluginDescriptor {
        val pluginDescriptor = createPluginDescriptorInstance()

        val attributes = manifest.mainAttributes
        val id = attributes.getValue(PLUGIN_ID)
        pluginDescriptor.pluginId = id

        val description = attributes.getValue(PLUGIN_DESCRIPTION)
        pluginDescriptor.pluginDescription = if (StringUtils.isNullOrEmpty(description)) {
            ""
        } else {
            description
        }

        val clazz = attributes.getValue(PLUGIN_CLASS)
        if (StringUtils.isNotNullOrEmpty(clazz)) {
            pluginDescriptor.setPluginClass(clazz)
        }

        val version = attributes.getValue(PLUGIN_VERSION)
        if (StringUtils.isNotNullOrEmpty(version)) {
            pluginDescriptor.setPluginVersion(version)
        }

        val provider = attributes.getValue(PLUGIN_PROVIDER)
        pluginDescriptor.setProvider(provider)
        val dependencies = attributes.getValue(PLUGIN_DEPENDENCIES)
        pluginDescriptor.setDependencies(dependencies)

        val requires = attributes.getValue(PLUGIN_REQUIRES)
        if (StringUtils.isNotNullOrEmpty(requires)) {
            pluginDescriptor.setRequires(requires)
        }

        pluginDescriptor.setLicense(attributes.getValue(PLUGIN_LICENSE))

        val sourceUrl = attributes.getValue(PLUGIN_SOURCE_URL)
        if (StringUtils.isNotNullOrEmpty(sourceUrl)) {
            pluginDescriptor.sourceUrl = sourceUrl
        }

        return pluginDescriptor
    }
}