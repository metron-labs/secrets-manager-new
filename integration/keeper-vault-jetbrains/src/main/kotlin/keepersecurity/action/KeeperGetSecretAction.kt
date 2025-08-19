package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.diagnostic.thisLogger
import keepersecurity.service.KeeperShellService
import org.json.JSONArray
import org.json.JSONObject

class KeeperGetSecretAction : AnAction("Get Keeper Secret") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR) ?: return

        object : Task.Backgroundable(project, "Fetching Keeper Secrets...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Using persistent Keeper shell..."

                // Step 1: Get list of records using persistent shell with retry logic
                val startTime = System.currentTimeMillis()
                val listJson = try {
                    // Add retry logic for the first run
                    executeCommandWithRetry("list --format json", 3)
                } catch (ex: Exception) {
                    logger.error("Failed to get record list from persistent shell", ex)
                    showError(project, "Failed to get Keeper record list: ${ex.message}")
                    return
                }

                val listDuration = System.currentTimeMillis() - startTime
                logger.info("⚡ List command executed in ${listDuration}ms")

                val records = try {
                    // Extract JSON from output (skip any non-JSON lines)
                    val jsonStart = listJson.indexOf('[')
                    if (jsonStart == -1) throw RuntimeException("No JSON array found in output")
                    val jsonString = listJson.substring(jsonStart)
                    JSONArray(jsonString)
                } catch (ex: Exception) {
                    logger.error("Failed to parse list JSON", ex)
                    showError(project, "Failed to parse Keeper list JSON: ${ex.message}")
                    return
                }

                val titles = mutableListOf<String>()
                val uidByTitle = mutableMapOf<String, String>()
                for (i in 0 until records.length()) {
                    val rec = records.getJSONObject(i)
                    val title = rec.optString("title", "Untitled")
                    val uid = rec.optString("record_uid")
                    if (uid.isNotBlank()) {
                        titles.add(title)
                        uidByTitle[title] = uid
                    }
                }

                if (titles.isEmpty()) {
                    showInfo(project, "No Keeper records found.")
                    return
                }

                // Step 2: Ask user to select record (UI thread)
                ApplicationManager.getApplication().invokeLater {
                    val selectedTitle = Messages.showEditableChooseDialog(
                        "Select Keeper Record:",
                        "Keeper Records",
                        null,
                        titles.toTypedArray(),
                        titles.first(),
                        null
                    ) ?: return@invokeLater

                    val selectedUid = uidByTitle[selectedTitle] ?: return@invokeLater

                    // Step 3: Fetch selected record details (background again)
                    object : Task.Backgroundable(project, "Fetching Record Details...", false) {
                        override fun run(indicator2: ProgressIndicator) {
                            indicator2.text = "Getting record details from persistent shell..."
                            
                            val recordStartTime = System.currentTimeMillis()
                            val recordJsonText = try {
                                KeeperShellService.executeCommand("get $selectedUid --format json", 30)
                            } catch (ex: Exception) {
                                logger.error("Failed to get record details from persistent shell", ex)
                                showError(project, "Failed to get Keeper record details: ${ex.message}")
                                return
                            }

                            val recordDuration = System.currentTimeMillis() - recordStartTime
                            logger.info("⚡ Get record command executed in ${recordDuration}ms")

                            if (recordJsonText.isBlank()) {
                                showError(project, "Failed to get Keeper record details.")
                                return
                            }

                            val recordJson = try {
                                // Extract JSON from output (skip any non-JSON lines)
                                val jsonStart = recordJsonText.indexOf('{')
                                if (jsonStart == -1) throw RuntimeException("No JSON object found in output")
                                val jsonString = recordJsonText.substring(jsonStart)
                                JSONObject(jsonString)
                            } catch (ex: Exception) {
                                logger.error("Failed to parse record JSON", ex)
                                showError(project, "Failed to parse Keeper record JSON: ${ex.message}")
                                return
                            }

                            val fieldOptions = mutableListOf<Pair<String, String>>()

                            // Process standard fields
                            val fieldsArray = recordJson.optJSONArray("fields")
                            if (fieldsArray != null) {
                                for (i in 0 until fieldsArray.length()) {
                                    val fieldObj = fieldsArray.getJSONObject(i)
                                    val type = fieldObj.optString("type", "")
                                    val valueArray = fieldObj.optJSONArray("value")
                                    if (type.isNotBlank() && valueArray != null && valueArray.length() > 0) {
                                        fieldOptions.add("$type (standard)" to type)
                                    }
                                }
                            }

                            // Process custom fields
                            val customArray = recordJson.optJSONArray("custom")
                            if (customArray != null) {
                                for (i in 0 until customArray.length()) {
                                    val customObj = customArray.getJSONObject(i)
                                    val label = customObj.optString("label", "")
                                    val valueArray = customObj.optJSONArray("value")
                                    if (label.isNotBlank() && valueArray != null && valueArray.length() > 0) {
                                        val key = label.replace("\\s".toRegex(), "_")
                                        fieldOptions.add("$label (custom)" to key)
                                    }
                                }
                            }

                            if (fieldOptions.isEmpty()) {
                                showInfo(project, "No fields with values found in selected record.")
                                return
                            }

                            logger.info("Found ${fieldOptions.size} fields in record '$selectedTitle'")

                            // Step 4: Ask user to pick field (UI thread)
                            ApplicationManager.getApplication().invokeLater {
                                val selectedFieldDisplay = Messages.showEditableChooseDialog(
                                    "Select field from record '$selectedTitle':",
                                    "Keeper Record Fields",
                                    null,
                                    fieldOptions.map { it.first }.toTypedArray(),
                                    fieldOptions.first().first,
                                    null
                                ) ?: return@invokeLater

                                val selectedFieldKey = fieldOptions.find { it.first == selectedFieldDisplay }?.second ?: return@invokeLater
                                val keeperNotation = "keeper://$selectedUid/field/$selectedFieldKey"

                                logger.info("Generated keeper notation: $keeperNotation")

                                // Step 5: Insert into editor (UI write action)
                                WriteCommandAction.runWriteCommandAction(project) {
                                    val doc = editor.document
                                    val caretOffset = editor.caretModel.offset
                                    doc.insertString(caretOffset, keeperNotation)
                                }

                                // Show success message
                                Messages.showInfoMessage(
                                    project,
                                    "✅ Keeper reference inserted!\n\n$keeperNotation\n\n⚡ Executed via persistent shell!",
                                    "Keeper Reference Added"
                                )
                            }
                        }
                    }.queue()
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

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Error")
        }
    }

    private fun showInfo(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "Info")
        }
    }
}