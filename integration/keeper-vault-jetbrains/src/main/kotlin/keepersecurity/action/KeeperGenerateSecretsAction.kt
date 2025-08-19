package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ide.util.PropertiesComponent
import keepersecurity.service.KeeperShellService
import org.json.JSONArray

class KeeperGenerateSecretsAction : AnAction("Keeper Generate Secrets") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val caret = editor.caretModel.currentCaret
        val selectionStart = caret.selectionStart
        val selectionEnd = caret.selectionEnd

        // Prompt for title *on the EDT* before background work
        val title = Messages.showInputDialog(
            project,
            "Enter Keeper record title:",
            "Record Title",
            null
        )?.takeIf { it.isNotBlank() } ?: run {
            showError("Title is required", project)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Keeper Secret", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Using persistent Keeper shell..."
                    
                    // Generate password using persistent shell
                    indicator.text = "Generating password..."
                    val startTime = System.currentTimeMillis()
                    
                    val password = generatePasswordWithRetry() ?: throw RuntimeException("Could not generate password.")
                    
                    val generateDuration = System.currentTimeMillis() - startTime
                    logger.info("⚡ Password generation completed in ${generateDuration}ms")

                    // Create Keeper record using persistent shell
                    indicator.text = "Creating Keeper record..."
                    val recordStartTime = System.currentTimeMillis()
                    
                    val recordUid = addKeeperRecordWithRetry(title, password, project)
                    
                    val recordDuration = System.currentTimeMillis() - recordStartTime
                    logger.info("⚡ Record creation completed in ${recordDuration}ms")

                    if (recordUid != null) {
                        val keeperReference = "keeper://$recordUid/field/password"
                        val totalDuration = System.currentTimeMillis() - startTime
                        logger.info("⚡ Total operation completed in ${totalDuration}ms")

                        // Replace text in editor on EDT
                        ApplicationManager.getApplication().invokeLater {
                            WriteCommandAction.runWriteCommandAction(project) {
                                document.replaceString(selectionStart, selectionEnd, keeperReference)
                                FileDocumentManager.getInstance().saveDocument(document)
                            }
                            Messages.showInfoMessage(
                                project,
                                "✅ Keeper record created!\n\n$keeperReference\n\n⚡ Generated via persistent shell in ${totalDuration}ms!",
                                "Keeper Secret Generated"
                            )
                        }
                    } else {
                        showError("Failed to create Keeper record.", project)
                    }
                } catch (ex: Exception) {
                    logger.error("Error generating Keeper secret", ex)
                    showError("Error: ${ex.message}", project)
                }
            }
        })
    }

    /**
     * Generate password using persistent shell with retry logic
     */
    private fun generatePasswordWithRetry(): String? {
        var lastException: Exception? = null
        
        for (attempt in 1..3) {
            try {
                logger.info("🔄 Password generation attempt $attempt/3")
                
                val output = KeeperShellService.executeCommand("generate -f json", 30)
                
                // Log the raw output for debugging
                logger.info("📥 Generate output (${output.length} chars): ${output.take(200)}${if (output.length > 200) "..." else ""}")
                
                // Check if output looks valid
                if (output.isBlank()) {
                    throw RuntimeException("Command returned empty output")
                }
                
                // Extract password from output
                val password = extractPasswordFromOutput(output)
                if (password != null) {
                    logger.info("✅ Generated password successfully on attempt $attempt")
                    return password
                }
                
                throw RuntimeException("No password found in generate output")
                
            } catch (ex: Exception) {
                lastException = ex
                logger.warn("❌ Password generation attempt $attempt failed: ${ex.message}")
                
                if (attempt < 3) {
                    logger.info("🔄 Retrying in 1 second...")
                    Thread.sleep(1000)
                }
            }
        }
        
        throw lastException ?: RuntimeException("Password generation failed after 3 attempts")
    }

    /**
     * Extract password from keeper generate output
     */
    private fun extractPasswordFromOutput(output: String): String? {
        try {
            // Skip warnings / logs until JSON array starts
            val jsonStart = output.indexOf('[')
            if (jsonStart == -1) {
                logger.warn("⚠️ No JSON array found in generate output")
                return null
            }
            
            val jsonPart = output.substring(jsonStart).trim()
            logger.debug("📋 Parsing generate JSON: ${jsonPart.take(100)}...")

            val jsonArray = JSONArray(jsonPart)
            if (jsonArray.length() == 0) {
                logger.warn("⚠️ Empty JSON array in generate output")
                return null
            }

            val password = jsonArray.getJSONObject(0).optString("password", null)
            logger.info("✅ Extracted password from JSON (length: ${password?.length ?: 0})")
            return password
            
        } catch (ex: Exception) {
            logger.error("❌ Failed to parse generate JSON from output: $output", ex)
            return null
        }
    }

    /**
     * Add Keeper record using persistent shell with retry logic
     */
    private fun addKeeperRecordWithRetry(title: String, password: String, project: Project): String? {
        var lastException: Exception? = null
        
        for (attempt in 1..3) {
            try {
                logger.info("🔄 Record creation attempt $attempt/3 for title: '$title'")
                
                val props = PropertiesComponent.getInstance(project)
                val folderUUID = props.getValue("keeper.folder.uuid")

                // Build command without 'keeper' prefix (we're in the shell)
                val cmdParts = mutableListOf(
                    "record-add",
                    "--title=\"$title\"",
                    "--record-type=login"
                )

                if (!folderUUID.isNullOrBlank()) {
                    cmdParts.add("--folder=\"$folderUUID\"")
                    logger.info("📁 Using folder UUID: $folderUUID")
                }

                cmdParts.add("password='$password'") // password must be single-quoted for CLI

                val command = cmdParts.joinToString(" ")
                logger.info("🔧 Running command: $command")
                
                val output = KeeperShellService.executeCommand(command, 45) // Longer timeout for record creation
                
                // Log the raw output for debugging
                logger.info("📥 Record-add output (${output.length} chars): ${output.take(300)}${if (output.length > 300) "..." else ""}")
                
                // Extract record UID from output
                val recordUid = extractRecordUidFromOutput(output)
                if (recordUid != null) {
                    logger.info("✅ Created record successfully on attempt $attempt: $recordUid")
                    return recordUid
                }
                
                throw RuntimeException("No record UID found in record-add output")
                
            } catch (ex: Exception) {
                lastException = ex
                logger.warn("❌ Record creation attempt $attempt failed: ${ex.message}")
                
                if (attempt < 3) {
                    logger.info("🔄 Retrying in 1 second...")
                    Thread.sleep(1000)
                }
            }
        }
        
        throw lastException ?: RuntimeException("Record creation failed after 3 attempts")
    }

    /**
     * Extract record UID from keeper record-add output
     */
    private fun extractRecordUidFromOutput(output: String): String? {
        try {
            // Look for typical Keeper record UID pattern (22 characters, alphanumeric + _ -)
            val uidMatch = Regex("""[A-Za-z0-9_-]{22}""").find(output)
            val recordUid = uidMatch?.value
            
            if (recordUid != null) {
                logger.info("✅ Extracted record UID: $recordUid")
            } else {
                logger.warn("⚠️ No record UID pattern found in output")
                logger.warn("📋 Full output: $output")
            }
            
            return recordUid
            
        } catch (ex: Exception) {
            logger.error("❌ Failed to extract record UID from output: $output", ex)
            return null
        }
    }

    private fun showError(message: String, project: Project) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Error")
        }
    }
}