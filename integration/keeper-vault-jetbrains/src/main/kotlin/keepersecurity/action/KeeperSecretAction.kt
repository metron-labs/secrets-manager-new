package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import keepersecurity.service.KeeperShellService
import org.json.JSONObject
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.UIManager

class KeeperSecretAction : AnAction("Run Keeper Securely") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        val envFile = chooseEnvFile(file)
        if (envFile == null || !envFile.exists()) {
            Messages.showErrorDialog(project, "No valid .env file selected.", "Error")
            return
        }

        // Ask user for the command to run their script
        val commandInput = Messages.showInputDialog(
            project,
            "Enter the command to run your script (e.g., python3 example.py):",
            "Run Script Command",
            Messages.getQuestionIcon(),
            "python3 ${file.name}",
            null
        )?.trim()

        if (commandInput.isNullOrEmpty()) {
            Messages.showWarningDialog(project, "No command provided, aborting.", "Cancelled")
            return
        }

        // 🔧 FIX: Save both files on EDT BEFORE starting background task
        ApplicationManager.getApplication().runWriteAction {
            val fileDocumentManager = FileDocumentManager.getInstance()
            
            // Save the main script file
            fileDocumentManager.saveDocument(document)
            logger.info("💾 Saved main script file to disk")
            
            // Save .env file if it's open in the IDE
            val envVirtualFile = file.parent.findChild(envFile.name)
            if (envVirtualFile != null) {
                val envDocument = fileDocumentManager.getDocument(envVirtualFile)
                if (envDocument != null) {
                    fileDocumentManager.saveDocument(envDocument)
                    logger.info("💾 Saved .env file to disk")
                }
            }
        }

        val originalContent = document.text

        object : Task.Backgroundable(project, "Fetching Keeper Secrets...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."
                
                // Check if Keeper shell is ready
                if (!isKeeperReady()) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "❌ Keeper is not ready!\nPlease run 'Check Keeper Authorization' first.",
                            "Keeper Not Ready"
                        )
                    }
                    return
                }

                indicator.text = "Processing .env and fetching secrets via persistent shell..."
                val startTime = System.currentTimeMillis()
                val result = processKeeperSecrets(originalContent, envFile, file, commandInput, indicator)
                val totalDuration = System.currentTimeMillis() - startTime
                
                logger.info("⚡ Total secret processing completed in ${totalDuration}ms")

                ApplicationManager.getApplication().invokeLater {
                    if (result.replacements > 0) {
                        val successMessage = buildSuccessMessage(result, totalDuration)
                        Messages.showInfoMessage(project, successMessage, "Secrets Injected")

                        if (result.scriptOutput.isNotBlank()) {
                            val textArea = JTextArea(result.scriptOutput.trim())
                            textArea.isEditable = false
                            val scrollPane = JScrollPane(textArea)
                            scrollPane.preferredSize = java.awt.Dimension(700, 420)
                            javax.swing.JOptionPane.showMessageDialog(
                                null,
                                scrollPane,
                                "Script Output",
                                javax.swing.JOptionPane.INFORMATION_MESSAGE
                            )
                        }
                    } else {
                        Messages.showWarningDialog(project, buildFailureMessage(result), "No Secrets Found")
                    }
                }
            }
        }.queue()
    }

    // ... chooseEnvFile and browseForEnvFile methods remain the same ...

    private fun chooseEnvFile(file: VirtualFile): File? {
        val defaultEnv = File(file.parent.path, ".env")
        val options = if (defaultEnv.exists()) {
            arrayOf(".env", "Browse")
        } else {
            arrayOf("Browse", "Browse") // Still 2 options to satisfy dialog
        }

        val choiceIndex = Messages.showChooseDialog(
            "Select .env file:",
            "Choose .env File",
            options,
            options[0],
            null
        )

        return when (choiceIndex) {
            0 -> if (defaultEnv.exists()) defaultEnv else browseForEnvFile()
            1 -> browseForEnvFile()
            else -> null
        }
    }

    private fun browseForEnvFile(): File? {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName())

        val chooser = JFileChooser().apply {
            dialogTitle = "Select .env File"
            fileSelectionMode = JFileChooser.FILES_ONLY
            isAcceptAllFileFilterUsed = false
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("*.env", "env")
            preferredSize = java.awt.Dimension(700, 500)
        }

        val result = chooser.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun buildSuccessMessage(result: ProcessResult, duration: Long): String {
        return buildString {
            appendLine("✅ Successfully injected ${result.replacements} secret(s)!")
            appendLine("⚡ Completed in ${duration}ms using persistent shell!")
            appendLine("💾 Script executed with latest saved changes!")
            if (result.errors.isNotEmpty()) {
                appendLine()
                appendLine("⚠️ Some errors occurred:")
                result.errors.take(3).forEach { appendLine("• $it") }
                if (result.errors.size > 3) appendLine("• ... and ${result.errors.size - 3} more")
            }
        }
    }

    private fun buildFailureMessage(result: ProcessResult): String {
        return if (result.errors.isNotEmpty()) {
            "❌ No secrets were injected.\n\nErrors:\n" +
                    result.errors.take(3).joinToString("\n") { "• $it" }
        } else {
            "❌ No Keeper references found in .env file!\n\nExpected format:\nKEY=keeper://UID/field/FieldName"
        }
    }

    private data class ProcessResult(
        val updatedContent: String,
        val replacements: Int,
        val errors: List<String>,
        val scriptOutput: String
    )

    private fun processKeeperSecrets(
        originalContent: String, 
        envFile: File, 
        sourceFile: VirtualFile, 
        commandLine: String,
        indicator: ProgressIndicator
    ): ProcessResult {
        val errors = mutableListOf<String>()
        val envVars = mutableMapOf<String, String>()
        val keeperPattern = Regex("""keeper://([A-Za-z0-9_-]+)/field/(\w+)""")
        
        // Parse .env file and identify Keeper references (files are already saved)
        val keeperRefs = mutableListOf<Triple<String, String, String>>() // key, uid, field
        
        try {
            envFile.readLines().forEachIndexed { index, line ->
                val parts = line.split("=", limit = 2)
                if (parts.size != 2) return@forEachIndexed
                val (key, value) = parts.map { it.trim() }
                val match = keeperPattern.matchEntire(value)
                if (match != null) {
                    val uid = match.groupValues[1]
                    val field = match.groupValues[2]
                    keeperRefs.add(Triple(key, uid, field))
                    logger.info("📋 Found Keeper ref: $key -> keeper://$uid/field/$field")
                }
            }
        } catch (e: Exception) {
            logger.error("❌ Failed to read .env file: ${envFile.absolutePath}", e)
            return ProcessResult(originalContent, 0, listOf("Failed to read .env file: ${e.message}"), "")
        }
        
        if (keeperRefs.isEmpty()) {
            return ProcessResult(originalContent, 0, listOf("No Keeper references found in .env file"), "")
        }
        
        logger.info("Found ${keeperRefs.size} Keeper references to process")
        indicator.text = "Fetching ${keeperRefs.size} secrets via persistent shell..."
        
        // Process each Keeper reference
        keeperRefs.forEachIndexed { index, (key, uid, field) ->
            indicator.text = "Fetching secret ${index + 1}/${keeperRefs.size}: $key"
            
            try {
                val secretStartTime = System.currentTimeMillis()
                val secretJson = getKeeperJsonFromShell(uid)
                val secretDuration = System.currentTimeMillis() - secretStartTime
                
                logger.info("⚡ Secret $key fetched in ${secretDuration}ms")
                
                val json = JSONObject(secretJson)
                val secret = json.optString(field, null)
                if (!secret.isNullOrEmpty()) {
                    envVars[key] = secret
                    logger.info("✅ Injected: $key=****** (${secret.length} chars)")
                } else {
                    errors.add("Field '$field' not found in Keeper record $uid")
                    logger.warn("⚠️ Field '$field' not found in record $uid")
                }
            } catch (e: Exception) {
                errors.add("Error fetching $uid/$field - ${e.message}")
                logger.error("❌ Error fetching Keeper secret for $uid/$field", e)
            }
        }
        
        indicator.text = "Running script with injected secrets..."
        val scriptOutput = runScriptWithEnv(envVars, commandLine, errors, File(sourceFile.parent.path))
        
        return ProcessResult(originalContent, envVars.size, errors, scriptOutput)
    }

    // ... rest of the methods remain the same ...
    
    private fun runScriptWithEnv(
        envVars: Map<String, String>, 
        commandLine: String, 
        errors: MutableList<String>, 
        fileParentDir: File
    ): String {
        return try {
            val commandParts = commandLine.split("""\s+""".toRegex())

            val pb = ProcessBuilder(commandParts).redirectErrorStream(true)
            pb.directory(fileParentDir)

            val env = pb.environment()
            env.putAll(envVars)
            
            logger.info("🚀 Running script with ${envVars.size} injected secrets")
            logger.info("📁 Working directory: ${fileParentDir.absolutePath}")
            logger.info("🐍 Command: ${commandParts.joinToString(" ")}")
            
            val process = pb.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            
            logger.info("Script completed with exit code: $exitCode")
            if (exitCode != 0) {
                errors.add("❌ Command exited with code $exitCode")
            }
            output
        } catch (ex: Exception) {
            logger.error("Failed to run script", ex)
            errors.add("❌ Failed to execute script: ${ex.message}")
            ""
        }
    }

    private fun getKeeperJsonFromShell(uid: String): String {
        return try {
            val output = executeCommandWithRetry("get $uid --format json --legacy", 3)
            
            val jsonStart = output.indexOf('{')
            if (jsonStart == -1) {
                throw RuntimeException("Failed to find JSON in Keeper CLI output")
            }
            
            val jsonString = output.substring(jsonStart).trim()
            logger.debug("📋 Extracted JSON for $uid (${jsonString.length} chars)")
            
            return jsonString
            
        } catch (ex: Exception) {
            logger.error("Failed to get Keeper JSON for $uid", ex)
            throw RuntimeException("Failed to fetch Keeper record $uid: ${ex.message}")
        }
    }

    private fun executeCommandWithRetry(command: String, maxRetries: Int): String {
        var lastException: Exception? = null
        
        for (attempt in 1..maxRetries) {
            try {
                logger.debug("🔄 Attempt $attempt/$maxRetries: $command")
                
                val output = KeeperShellService.executeCommand(command, 30)
                
                if (output.isBlank()) {
                    throw RuntimeException("Command returned empty output")
                }
                
                if (command.startsWith("get") && !output.contains('{')) {
                    logger.warn("⚠️ Output doesn't contain JSON, might be startup messages")
                    
                    if (attempt < maxRetries) {
                        logger.info("🔄 Retrying in 1 second...")
                        Thread.sleep(1000)
                        continue
                    } else {
                        throw RuntimeException("No JSON found in output after $maxRetries attempts")
                    }
                }
                
                logger.debug("✅ Got valid output on attempt $attempt")
                return output
                
            } catch (ex: Exception) {
                lastException = ex
                logger.warn("❌ Attempt $attempt failed: ${ex.message}")
                
                if (attempt < maxRetries) {
                    logger.info("🔄 Retrying in 1 second...")
                    Thread.sleep(1000)
                } else {
                    logger.error("❌ All $maxRetries attempts failed")
                }
            }
        }
        
        throw lastException ?: RuntimeException("Command failed after $maxRetries attempts")
    }

    private fun isKeeperReady(): Boolean {
        return try {
            logger.info("🔍 Checking if Keeper shell is ready...")
            
            if (!KeeperShellService.isReady()) {
                logger.info("🚀 Starting Keeper shell...")
                if (!KeeperShellService.startShell()) {
                    logger.error("❌ Failed to start Keeper shell")
                    return false
                }
            }
            
            val output = KeeperShellService.executeCommand("this-device", 10)
            val isReady = output.contains("Persistent Login: ON", ignoreCase = true) ||
                         output.contains("Status: SUCCESSFUL", ignoreCase = true) ||
                         output.contains("Device Name:", ignoreCase = true)
            
            logger.info("Keeper ready status: $isReady")
            return isReady
            
        } catch (ex: Exception) {
            logger.error("Error checking Keeper readiness", ex)
            false
        }
    }
}