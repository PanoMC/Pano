package com.panomc.platform.server.files

import com.panomc.platform.node.ManagedServerFileClient
import com.panomc.platform.route.api.panel.server.files.PanelDownloadServerFileAPI
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileDownloadVirtualPathTest {
    @Test
    fun `the file manager never downloads a backup through its virtual path`() {
        assertTrue(PanelDownloadServerFileAPI.isRefusedPath(ManagedServerFileClient.normalisePath("@backup/abc")))
        assertTrue(PanelDownloadServerFileAPI.isRefusedPath(ManagedServerFileClient.normalisePath("./@backup/safety-1")))
        assertTrue(PanelDownloadServerFileAPI.isRefusedPath(""))
        assertFalse(PanelDownloadServerFileAPI.isRefusedPath("plugins/@scoped/a.yml"))
        assertFalse(PanelDownloadServerFileAPI.isRefusedPath("server.properties"))
    }
}
