package com.panomc.platform

import org.pf4j.DefaultPluginDescriptor
import org.pf4j.PluginDescriptor

class PanoPluginDescriptor : DefaultPluginDescriptor() {
    lateinit var name: String
    var description: String? = null
    lateinit var developer: String
    var sourceUrl: String? = null

    @Deprecated("Do not use", level = DeprecationLevel.HIDDEN)
    override fun getProvider(): String? {
        return super.getProvider()
    }

    @Deprecated("Do not use", level = DeprecationLevel.HIDDEN)
    override fun getPluginDescription(): String? {
        return super.getPluginDescription()
    }


    public override fun setPluginId(pluginId: String): DefaultPluginDescriptor {
        return super.setPluginId(pluginId)
    }

    public override fun setPluginClass(pluginClassName: String?): PluginDescriptor {
        return super.setPluginClass(pluginClassName)
    }

    public override fun setPluginVersion(version: String?): DefaultPluginDescriptor {
        return super.setPluginVersion(version)
    }

    public override fun setDependencies(dependencies: String?): PluginDescriptor {
        return super.setDependencies(dependencies)
    }

    public override fun setRequires(requires: String?): PluginDescriptor {
        return super.setRequires(requires)
    }
}