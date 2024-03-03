package com.panomc.platform

import org.pf4j.*
import java.nio.file.Path

class PluginManager(importPaths: List<Path>) : DefaultPluginManager(importPaths) {
    companion object {
        internal val pluginEventManager = PluginEventManager()
    }

    override fun startPlugins() {
        super.startPlugins()
    }

    override fun createPluginDescriptorFinder(): CompoundPluginDescriptorFinder {
        return CompoundPluginDescriptorFinder() // Demo is using the Manifest file
            // PropertiesPluginDescriptorFinder is commented out just to avoid error log
            //.add(PropertiesPluginDescriptorFinder())
            .add(ManifestPluginDescriptorFinder())
    }

    override fun createPluginFactory(): PluginFactory {
        return PluginFactory()
    }

    override fun createPluginLoader(): PluginLoader {
        return CompoundPluginLoader()
            .add(PanoPluginLoader(this)) { this.isNotDevelopment }
    }

    override fun loadPlugins() {
        super.loadPlugins()
    }
}