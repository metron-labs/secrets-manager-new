package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.diagnostic.thisLogger
import keepersecurity.service.KeeperShellService
import org.json.JSONArray

class KeeperFolderSelectAction : AnAction("Get Keeper Folder") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        object : Task.Backgroundable(project, "Fetching Keeper Folders...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."
                
                val folderMap: List<Pair<String, String>> = try {
                    // This should be FAST after the first call!
                    val startTime = System.currentTimeMillis()
                    
                    // Add retry logic for the first run
                    val output = executeCommandWithRetry("ls --format=json -f -R", 3)
                    
                    val duration = System.currentTimeMillis() - startTime
                    logger.info("⚡ Command executed in ${duration}ms")
                    
                    parseKeeperFolders(output)
                    
                } catch (ex: Exception) {
                    logger.error("Failed to get folders from persistent shell", ex)
                    showError(project, "Failed to retrieve Keeper folders: ${ex.message}")
                    return
                }

                if (folderMap.isEmpty()) {
                    showError(project, "No folders found in Keeper vault.")
                    return
                }

                // UI code remains the same
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    val folderNames = folderMap.map { it.first }.toTypedArray()
                    val selected = Messages.showEditableChooseDialog(
                        "Select a Keeper Folder:",
                        "Keeper Folder Selection",
                        null,
                        folderNames,
                        folderNames.firstOrNull(),
                        null
                    ) ?: return@invokeLater

                    val selectedFolder = folderMap.find { it.first == selected }
                    if (selectedFolder != null) {
                        val props = PropertiesComponent.getInstance(project)
                        props.setValue("keeper.folder.name", selectedFolder.first)
                        props.setValue("keeper.folder.uuid", selectedFolder.second)

                        Messages.showInfoMessage(
                            project,
                            "✅ Folder '${selectedFolder.first}' with UUID '${selectedFolder.second}' has been saved.\n⚡ Executed in persistent shell!",
                            "Folder Saved"
                        )
                    }
                }
            }
        }.queue()
    }

    /**
     * Execute command with retry logic to handle shell startup timing
     */
    private fun executeCommandWithRetry(command: String, maxRetries: Int): String {
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                logger.info("🔄 Attempt $attempt/$maxRetries: $command")
                
                val output = KeeperShellService.executeCommand(command, 45) // Longer timeout for first run
                
                // Log the raw output for debugging
                logger.info("📥 Raw output (${output.length} chars): ${output.take(200)}${if (output.length > 200) "..." else ""}")
                
                // Check if output looks valid
                if (output.isBlank()) {
                    throw RuntimeException("Command returned empty output")
                }
                
                // Check if it contains JSON
                if (!output.contains('[')) {
                    logger.warn("⚠️ Output doesn't contain JSON array, might be startup messages")
                    logger.warn("📋 Full output: $output")
                    
                    if (attempt < maxRetries) {
                        logger.info("🔄 Retrying in 2 seconds...")
                        Thread.sleep(2000)
                        continue
                    } else {
                        throw RuntimeException("No JSON array found in output after $maxRetries attempts")
                    }
                }
                
                logger.info("✅ Got valid JSON output on attempt $attempt")
                return output
                
            } catch (ex: Exception) {
                lastException = ex
                logger.warn("❌ Attempt $attempt failed: ${ex.message}")
                
                if (attempt < maxRetries) {
                    logger.info("🔄 Retrying in 2 seconds...")
                    Thread.sleep(2000)
                } else {
                    logger.error("❌ All $maxRetries attempts failed")
                }
            }
        }
        
        // If we get here, all retries failed
        throw lastException ?: RuntimeException("Command failed after $maxRetries attempts")
    }

    private fun parseKeeperFolders(output: String): List<Pair<String, String>> {
        try {
            val jsonStart = output.indexOf('[')
            if (jsonStart == -1) {
                logger.error("📋 No JSON array found in output: $output")
                throw RuntimeException("No JSON found in output.")
            }
            
            val jsonString = output.substring(jsonStart)
            logger.debug("📋 Parsing JSON: ${jsonString.take(100)}...")

            val jsonArray = JSONArray(jsonString)
            val result = (0 until jsonArray.length()).mapNotNull { i ->
                val obj = jsonArray.getJSONObject(i)
                val name = obj.optString("name")
                val uuid = obj.optString("folder_uid")
                if (name.isNotBlank() && uuid.isNotBlank()) name to uuid else null
            }
            
            logger.info("✅ Parsed ${result.size} folders from JSON")
            return result
            
        } catch (ex: Exception) {
            logger.error("❌ Failed to parse JSON from output: $output", ex)
            throw RuntimeException("Failed to parse folder JSON: ${ex.message}")
        }
    }

    private fun showError(project: Project, message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Error")
        }
    }
}