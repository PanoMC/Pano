package com.panomc.platform

import org.pf4j.DefaultPluginDescriptor
import org.pf4j.PluginDescriptor

class PanoPluginDescriptor : DefaultPluginDescriptor() {
    lateinit var name: String
    var description: String? = null
    lateinit var panoVersion: String
    lateinit var developer: String
    var sourceUrl: String? = null

    /**
     * Marks the plugin as freemium: free to install and run, with optional paid features
     * unlocked through in-addon purchases. Set by the plugin's `freemium` manifest attribute.
     *
     * Mutually exclusive with premium (DRM): a freemium plugin must not call
     * [com.panomc.platform.license.LicenseManager.requireLicense], which rejects it outright.
     * Feature gating goes through [com.panomc.platform.api.PanoPlugin.hasTier] instead.
     */
    var freemium: Boolean = false

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