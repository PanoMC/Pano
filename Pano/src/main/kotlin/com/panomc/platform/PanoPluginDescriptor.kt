package com.panomc.platform

import org.pf4j.DefaultPluginDescriptor
import org.pf4j.PluginDescriptor

class PanoPluginDescriptor : DefaultPluginDescriptor() {
    var sourceUrl: String? = null

    public override fun setPluginId(pluginId: String): DefaultPluginDescriptor {
        return super.setPluginId(pluginId)
    }

    public override fun setPluginDescription(pluginDescription: String?): PluginDescriptor {
        return super.setPluginDescription(pluginDescription)
    }

    public override fun setPluginClass(pluginClassName: String?): PluginDescriptor {
        return super.setPluginClass(pluginClassName)
    }

    public override fun setPluginVersion(version: String?): DefaultPluginDescriptor {
        return super.setPluginVersion(version)
    }

    public override fun setProvider(provider: String?): PluginDescriptor {
        return super.setProvider(provider)
    }

    public override fun setDependencies(dependencies: String?): PluginDescriptor {
        return super.setDependencies(dependencies)
    }

    public override fun setRequires(requires: String?): PluginDescriptor {
        return super.setRequires(requires)
    }
}