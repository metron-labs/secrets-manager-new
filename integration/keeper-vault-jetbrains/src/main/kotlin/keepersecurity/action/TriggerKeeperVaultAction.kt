package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import java.io.BufferedReader
import java.io.InputStreamReader

class TriggerKeeperVaultAction : AnAction("Get Keeper Secrets") {

    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        val originalContent = document.text
        logger.info("Starting Keeper secret injection for file: ${file.name}")

        // Run in background thread
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Fetching Keeper Secrets", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Processing Keeper secrets..."
                    indicator.fraction = 0.1

                    var replacementCount = 0
                    val errorMessages = mutableListOf<String>()

                    val lines = originalContent.lines()
                    val updatedLines = mutableListOf<String>()

                    for ((lineIndex, line) in lines.withIndex()) {
                        if (indicator.isCanceled) {
                            return
                        }

                        indicator.fraction = 0.1 + (0.8 * lineIndex / lines.size)
                        indicator.text2 = "Processing line ${lineIndex + 1} of ${lines.size}"

                        logger.info("Processing line ${lineIndex + 1}: $line")

                        var updatedLine = line

                        // Simple regex to find keeper references: uid/field/fieldname
                        val keeperPattern = Regex("""([A-Za-z0-9_-]+)/field/(\w+)""")
                        val matches = keeperPattern.findAll(line).toList()

                        for (match in matches) {
                            try {
                                val uid = match.groupValues[1]
                                val fieldName = match.groupValues[2]
                                val fullMatch = match.value

                                logger.info("Found Keeper reference - UID: $uid, Field: $fieldName")

                                // Call keeper CLI directly
                                val secretValue = getKeeperSecret(uid, fieldName)

                                if (secretValue != null && secretValue.isNotEmpty()) {
                                    updatedLine = updatedLine.replace(fullMatch, secretValue)
                                    replacementCount++
                                    logger.info("Successfully replaced $fullMatch with secret value")
                                } else {
                                    val errorMsg = "ERROR_NOT_FOUND_${uid}_${fieldName}"
                                    updatedLine = updatedLine.replace(fullMatch, errorMsg)
                                    errorMessages.add("Line ${lineIndex + 1}: Could not find field '$fieldName' for UID '$uid'")
                                    logger.warn("Could not find field '$fieldName' for UID '$uid'")
                                }

                            } catch (ex: Exception) {
                                val errorMsg = "ERROR_EXCEPTION_${match.groupValues[1]}"
                                updatedLine = updatedLine.replace(match.value, errorMsg)
                                errorMessages.add("Line ${lineIndex + 1}: Exception - ${ex.message}")
                                logger.error("Exception processing match: ${match.value}", ex)
                            }
                        }

                        updatedLines.add(updatedLine)
                    }

                    indicator.fraction = 0.9
                    indicator.text2 = "Updating document..."

                    val updatedContent = updatedLines.joinToString("\n")

                    // Update UI on EDT
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                            document.setText(updatedContent)
                        }

                        val message = if (replacementCount > 0) {
                            val successMsg = "✅ Successfully injected $replacementCount secret(s)!"
                            val errorMsg = if (errorMessages.isNotEmpty()) {
                                "\n\n⚠️ Errors:\n${errorMessages.take(5).joinToString("\n")}" +
                                        if (errorMessages.size > 5) "\n... and ${errorMessages.size - 5} more errors" else ""
                            } else ""
                            successMsg + errorMsg
                        } else {
                            "❌ No Keeper references found!\n\n" +
                                    "Supported format: uid/field/fieldname\n" +
                                    "Example: jnPuLYWXt7b6Ym-_9OCvFA/field/login\n\n" +
                                    "Supported fields: login, password, url, notes, title"
                        }

                        if (replacementCount > 0) {
                            Messages.showInfoMessage(project, message, "Keeper Secrets Injected")
                        } else {
                            Messages.showWarningDialog(project, message, "No Secrets Found")
                        }
                    }

                    indicator.fraction = 1.0
                    indicator.text2 = "Complete"

                } catch (ex: Exception) {
                    logger.error("Fatal error in TriggerKeeperVaultAction", ex)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "❌ Fatal error: ${ex.message}\n\n" +
                                    "Make sure Keeper CLI is installed and you're logged in:\n" +
                                    "1. pip install keepercommander\n" +
                                    "2. keeper login your-email@example.com",
                            "Keeper Vault Error"
                        )
                    }
                }
            }
        })
    }

    /**
     * Get a secret from Keeper using CLI directly
     */
    private fun getKeeperSecret(uid: String, fieldName: String): String? {
        return try {
            logger.info("Executing: keeper get $uid --format=json")

            val processBuilder = ProcessBuilder("keeper", "get", uid, "--format=json")
            val process = processBuilder.start()

            val output = StringBuilder()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }

            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val jsonOutput = output.toString()
                logger.info("Keeper CLI output: $jsonOutput")
                extractFieldFromJson(jsonOutput, fieldName)
            } else {
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                val errorOutput = StringBuilder()
                while (errorReader.readLine().also { line = it } != null) {
                    errorOutput.appendLine(line)
                }
                logger.error("Keeper CLI failed with exit code $exitCode: ${errorOutput.toString()}")
                null
            }
        } catch (e: Exception) {
            logger.error("Error executing keeper CLI for UID: $uid", e)
            null
        }
    }

    /**
     * Extract field value from Keeper JSON output
     */
    private fun extractFieldFromJson(jsonOutput: String, fieldName: String): String? {
        return try {
            val lines = jsonOutput.lines()

            for (line in lines) {
                val trimmed = line.trim()
                val searchKey = "\"${fieldName.lowercase()}\""

                if (trimmed.contains(searchKey)) {
                    val colonIndex = trimmed.indexOf(":")
                    if (colonIndex != -1) {
                        val value = trimmed.substring(colonIndex + 1).trim()
                        return value.removeSurrounding("\"").removeSuffix(",")
                    }
                }
            }

            // If exact field not found, try common mappings
            val alternativeKey = when (fieldName.lowercase()) {
                "login" -> "\"username\""
                "username" -> "\"login\""
                else -> null
            }

            if (alternativeKey != null) {
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.contains(alternativeKey)) {
                        val colonIndex = trimmed.indexOf(":")
                        if (colonIndex != -1) {
                            val value = trimmed.substring(colonIndex + 1).trim()
                            return value.removeSurrounding("\"").removeSuffix(",")
                        }
                    }
                }
            }

            logger.warn("Field '$fieldName' not found in JSON output")
            null
        } catch (e: Exception) {
            logger.error("Error parsing JSON for field '$fieldName'", e)
            null
        }
    }
}