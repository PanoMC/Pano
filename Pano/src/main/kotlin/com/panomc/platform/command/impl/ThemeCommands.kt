package com.panomc.platform.command.impl

import com.panomc.platform.AppConstants
import com.panomc.platform.UIManager
import com.panomc.platform.command.Command
import com.panomc.platform.command.CommandExecutor
import com.panomc.platform.command.CommandSender
import com.panomc.platform.config.ConfigManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Route
import io.vertx.ext.web.Router
import kotlinx.coroutines.runBlocking
import org.springframework.stereotype.Component
import java.io.File

@Component
class ThemeCommands(
    private val router: Router,
    private val uiManager: UIManager,
    private val configManager: ConfigManager,
    private val databaseManager: DatabaseManager
) : CommandExecutor {
    @Command(name = "themes", aliases = ["theme"], description = "Theme management commands")
    fun onThemeCommand(sender: CommandSender, args: Array<String>) {
        if (args.isEmpty() || args[0].lowercase() == "help") {
            sender.sendMessage("\u001B[36m--- Theme Commands ---\u001B[0m")
            sender.sendMessage("\u001B[33mthemes list\u001B[0m - List all installed themes")
            sender.sendMessage("\u001B[33mthemes start\u001B[0m - Start the active theme")
            sender.sendMessage("\u001B[33mthemes stop\u001B[0m - Stop the active theme")
            sender.sendMessage("\u001B[33mthemes set <theme>\u001B[0m - Switch to another theme")
            sender.sendMessage("\u001B[33mthemes remove <theme>\u001B[0m - Remove a theme")
            return
        }

        if (args[0].lowercase() == "list") {
            sender.sendMessage("\u001B[36m--- Installed Themes ---\u001B[0m")
            uiManager.installedThemeList.forEach { theme ->
                val isActive = theme.id == uiManager.activeTheme
                val isStarted = uiManager.activatedUIList.containsKey(Route.Type.THEME_UI) && isActive
                val prefix = if (isActive) "\u001B[32m* " else "  "
                val status = if (isActive) " (\u001B[32mActive\u001B[0m${if (isStarted) ", \u001B[32mStarted\u001B[0m" else ", \u001B[31mStopped\u001B[0m"})" else ""
                sender.sendMessage("$prefix${theme.id}\u001B[0m v${theme.version} - ${theme.author}$status")
            }
            return
        }

        val sub = args[0].lowercase()

        when (sub) {
            "stop" -> {
                if (!uiManager.activatedUIList.containsKey(Route.Type.THEME_UI)) {
                    sender.sendMessage("\u001B[31mTheme is already stopped.\u001B[0m")
                    return
                }
                uiManager.stopUI(uiManager.activeTheme)
                uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)
                sender.sendMessage("\u001B[32mStopped currently active theme: ${uiManager.activeTheme}\u001B[0m")
            }
            "start" -> {
                if (uiManager.activatedUIList.containsKey(Route.Type.THEME_UI)) {
                    sender.sendMessage("\u001B[31mTheme is already started.\u001B[0m")
                    return
                }
                uiManager.startUI(uiManager.activeTheme)
                uiManager.activateThemeUI(router, uiManager.activeTheme)
                sender.sendMessage("\u001B[32mStarted currently active theme: ${uiManager.activeTheme}\u001B[0m")
            }
            "set", "switch" -> {
                if (args.size < 2) {
                    sender.sendMessage("\u001B[31mUsage: themes $sub <id>\u001B[0m")
                    return
                }
                val id = args[1]
                val theme = uiManager.installedThemeList.find { it.id == id }
                if (theme == null) {
                    sender.sendMessage("\u001B[31mTheme '$id' not found.\u001B[0m")
                    return
                }

                if (theme.id == uiManager.activeTheme) {
                    sender.sendMessage("\u001B[33mTheme '$id' is already active.\u001B[0m")
                    return
                }

                sender.sendMessage("\u001B[33mSwitching theme to '$id'...\u001B[0m")
                
                // 1. Update config
                configManager.config.currentTheme = theme.id
                configManager.saveConfig()
                
                // 2. Stop old theme and disable route
                uiManager.stopUI(uiManager.activeTheme)
                uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)
                
                // 3. Start new theme and activate route
                uiManager.startUI(theme.id)
                uiManager.activateThemeUI(router, theme.id)
                
                sender.sendMessage("\u001B[32mTheme switched to '$id'.\u001B[0m")
            }
            "remove", "delete" -> {
                if (args.size < 2) {
                    sender.sendMessage("\u001B[31mUsage: themes $sub <id>\u001B[0m")
                    return
                }
                val id = args[1]
                val theme = uiManager.installedThemeList.find { it.id == id }
                if (theme == null) {
                    sender.sendMessage("\u001B[31mTheme '$id' not found.\u001B[0m")
                    return
                }

                if (theme.installedBy == UIManager.Companion.InstalledBy.SYSTEM) {
                    sender.sendMessage("\u001B[31mCannot remove system theme: $id\u001B[0m")
                    return
                }

                sender.sendMessage("\u001B[33mRemoving theme '$id'...\u001B[0m")

                val config = configManager.config
                val currentTheme = config.currentTheme

                // If deleting active/current theme, fallback to default
                if (theme.id == currentTheme) {
                    config.currentTheme = AppConstants.DEFAULT_THEME_ID
                    configManager.saveConfig()
                }

                if (uiManager.activeTheme == theme.id) {
                    uiManager.stopUI(theme.id)
                    uiManager.disableUIOnRoute(router, Route.Type.THEME_UI)

                    if (config.initUi) {
                        uiManager.startUI(AppConstants.DEFAULT_THEME_ID)
                    }

                    uiManager.activateThemeUI(router, AppConstants.DEFAULT_THEME_ID)
                }

                // Delete folder
                val themeFolder = File(AppConstants.THEMES_FOLDER_PATH, theme.id)
                if (themeFolder.exists()) {
                    themeFolder.deleteRecursively()
                }

                // Cleanup DB
                runBlocking {
                    val sqlClient = databaseManager.getSqlClient()
                    databaseManager.resourceHashDao.deleteByHash(theme.hash, sqlClient)
                }

                // Reload themes
                uiManager.reloadInstalledThemes()

                sender.sendMessage("\u001B[32mRemoved theme: $id\u001B[0m")
            }
            else -> sender.sendMessage("\u001B[31mUnknown theme subcommand: $sub\u001B[0m")
        }
    }
}
