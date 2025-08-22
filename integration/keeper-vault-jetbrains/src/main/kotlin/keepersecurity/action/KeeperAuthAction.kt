package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import keepersecurity.service.KeeperShellService
import keepersecurity.util.KeeperCommandUtils

class KeeperAuthAction : AnAction("Check Keeper Authorization") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        object : Task.Backgroundable(project, "Checking Keeper Authentication...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logger.info("KEEPER AUTH: Checking biometric and persistent login")
                    
                    indicator.text = "Connecting to Keeper shell..."
                    
                    // Ensure shell is ready
                    if (!KeeperShellService.isReady()) {
                        indicator.text = "Starting Keeper shell..."
                        if (!KeeperShellService.startShell()) {
                            showError(project, "Failed to start Keeper shell. Please check your Keeper CLI installation.")
                            return
                        }
                    }
                    
                    // Step 1: Try biometric authentication first
                    indicator.text = "Checking biometric authentication..."
                    val biometricResult = checkBiometricAuth()
                    
                    if (biometricResult == BiometricStatus.SUCCESS) {
                        logger.info("Biometric authentication successful")
                        showSuccess(project, 
                            "Keeper Authentication Successful!\n\n" +
                            "Biometric authentication is working correctly.\n" +
                            "All actions will use the persistent shell for maximum speed!"
                        )
                        return
                    }
                    
                    logger.info("Biometric not working, checking persistent login...")
                    
                    // Step 2: If biometric failed/unavailable, try persistent login
                    indicator.text = "Checking persistent login..."
                    val persistentResult = checkPersistentLogin()
                    
                    if (persistentResult == PersistentStatus.SUCCESS) {
                        logger.info("Persistent login successful")
                        showSuccess(project, 
                            "Keeper Authentication Successful!\n\n" +
                            "Persistent login is enabled and working.\n" +
                            "Consider enabling biometric authentication for enhanced security.\n" +
                            "All actions will use the persistent shell for maximum speed!"
                        )
                        return
                    }
                    
                    // Step 3: Neither biometric nor persistent login worked
                    logger.error("Neither biometric nor persistent login is available")
                    showAuthRequiredError(project)
                    
                } catch (ex: Exception) {
                    logger.error("KEEPER AUTH: Exception during authentication check", ex)
                    showError(project, "Unexpected error during authentication check: ${ex.message}")
                }
            }
        }.queue()
    }
    
    private fun showAuthRequiredError(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(
                project,
                "Keeper Authentication Required!\n\n" +
                "You need to enable either biometric authentication or persistent login.\n\n" +
                "Option 1 - Enable Biometric Authentication:\n" +
                "1. Run 'keeper shell' in terminal\n" +
                "2. Run 'biometric register'\n" +
                "3. Follow the prompts\n\n" +
                "Option 2 - Enable Persistent Login:\n" +
                "1. Run 'keeper shell' in terminal\n" +
                "2. Run 'this-device register'\n" +
                "3. Run 'this-device persistent-login on'\n\n" +
                "Then try this action again.",
                "Authentication Setup Required"
            )
        }
    }

    private enum class BiometricStatus {
        SUCCESS, NOT_REGISTERED, NOT_SUPPORTED, FAILED
    }
    
    private enum class PersistentStatus {
        SUCCESS, NOT_ENABLED, FAILED
    }
    
    private fun checkBiometricAuth(): BiometricStatus {
        return try {
            logger.info("Checking biometric authentication...")
            
            val output = KeeperCommandUtils.executeCommandWithRetry(
                "biometric verify",
                KeeperCommandUtils.RetryConfig(
                    maxRetries = 2,
                    timeoutSeconds = 50,
                    retryDelayMs = 2000,
                    logLevel = KeeperCommandUtils.LogLevel.INFO,
                    validation = KeeperCommandUtils.ValidationConfig(
                        customValidator = { result ->
                            // Should contain biometric results, NOT sync status
                            val hasBiometricResult = result.contains("Biometric Authentication", ignoreCase = true) ||
                                                result.contains("Status:", ignoreCase = true)
                            
                            val isNotSyncStatus = !result.contains("Decrypted [") && 
                                                !result.contains("record(s)") &&
                                                !result.contains("breachwatch")
                            
                            hasBiometricResult && isNotSyncStatus
                        }
                    )
                ),
                logger
            )
            
            logger.info("Biometric verify FINAL output: $output")
            
            when {
                // Success patterns - exactly what your terminal shows
                output.contains("Status: SUCCESSFUL", ignoreCase = true) -> {
                    logger.info("Biometric verification successful")
                    BiometricStatus.SUCCESS
                }
                
                // Not registered patterns
                output.contains("not registered", ignoreCase = true) || 
                output.contains("no biometric", ignoreCase = true) -> {
                    logger.info("Biometric not registered")
                    BiometricStatus.NOT_REGISTERED
                }
                
                // Not supported patterns  
                output.contains("not supported", ignoreCase = true) ||
                output.contains("not available", ignoreCase = true) -> {
                    logger.info("Biometric not supported on this device")
                    BiometricStatus.NOT_SUPPORTED
                }
                
                else -> {
                    logger.warn("Biometric result unclear")
                    logger.warn("Full output: $output")
                    BiometricStatus.FAILED
                }
            }
        } catch (ex: Exception) {
            logger.error("Biometric check exception", ex)
            BiometricStatus.FAILED
        }
    }
    
    private fun setupBiometric(): Boolean {
        return try {
            logger.info("🔧 Setting up biometric authentication...")
            
            // Register biometric
            val registerOutput = KeeperShellService.executeCommand("biometric register", 30)
            logger.info("Biometric register output: $registerOutput")
            
            if (registerOutput.contains("successful", ignoreCase = true) ||
                registerOutput.contains("registered", ignoreCase = true)) {
                
                // Verify it works
                Thread.sleep(2000) // Give it time to register
                val verifyOutput = KeeperShellService.executeCommand("biometric verify", 15)
                logger.info("Biometric verify after register: $verifyOutput")
                
                val success = verifyOutput.contains("Status: SUCCESSFUL", ignoreCase = true)
                logger.info("Biometric setup result: $success")
                return success
            } else {
                logger.warn("Biometric registration failed: $registerOutput")
                return false
            }
        } catch (ex: Exception) {
            logger.error("Biometric setup failed", ex)
            false
        }
    }
    
    private fun checkPersistentLogin(): PersistentStatus {
        return try {
            logger.info("Checking persistent login status...")
            
            val output = KeeperCommandUtils.executeCommandWithRetry(
                "this-device",
                KeeperCommandUtils.RetryConfig(
                    maxRetries = 3,
                    timeoutSeconds = 15,
                    retryDelayMs = 2000,
                    logLevel = KeeperCommandUtils.LogLevel.INFO,
                    validation = KeeperCommandUtils.ValidationConfig(
                        customValidator = { result ->
                            // Should contain device info, NOT sync status or biometric results
                            val hasDeviceInfo = result.contains("Device Name:", ignoreCase = true) ||
                                            result.contains("Persistent Login:", ignoreCase = true)
                            
                            val isNotSyncStatus = !result.contains("Decrypted [") && 
                                                !result.contains("record(s)") &&
                                                !result.contains("breachwatch") &&
                                                !result.contains("Biometric Authentication")
                            
                            hasDeviceInfo && isNotSyncStatus
                        }
                    )
                ),
                logger
            )
            
            logger.info("This-device FINAL output: $output")
            
            when {
                // Success patterns - exactly what your terminal shows
                output.contains("Persistent Login: ON", ignoreCase = true) -> {
                    logger.info("Persistent login is enabled")
                    PersistentStatus.SUCCESS
                }
                
                // Not enabled patterns
                output.contains("Persistent Login: OFF", ignoreCase = true) -> {
                    logger.info("Persistent login is disabled")
                    PersistentStatus.NOT_ENABLED
                }
                
                else -> {
                    logger.warn("Persistent login status unclear")
                    logger.warn("Full output: $output")
                    PersistentStatus.FAILED
                }
            }
        } catch (ex: Exception) {
            logger.error("Persistent login check exception", ex)
            PersistentStatus.FAILED
        }
    }
    
    private fun setupPersistentLogin(): Boolean {
        return try {
            logger.info("🔧 Setting up persistent login...")
            
            // Step 1: Register device
            val registerOutput = KeeperShellService.executeCommand("this-device register", 20)
            logger.info("Device register output: $registerOutput")
            
            // Step 2: Enable persistent login
            Thread.sleep(1000) // Give it time to register
            val enableOutput = KeeperShellService.executeCommand("this-device persistent-login on", 15)
            logger.info("Persistent login enable output: $enableOutput")
            
            // Step 3: Verify it's enabled
            Thread.sleep(1000) // Give it time to enable
            val verifyOutput = KeeperShellService.executeCommand("this-device", 10)
            logger.info("Verification output: $verifyOutput")
            
            val success = verifyOutput.contains("Persistent Login: ON", ignoreCase = true) ||
                         verifyOutput.contains("Persistent Login:\\s*ON".toRegex())
            
            logger.info("Persistent login setup result: $success")
            return success
            
        } catch (ex: Exception) {
            logger.error("Persistent login setup failed", ex)
            false
        }
    }
    
    private fun showSuccess(project: Project, message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "Keeper Authentication Success")
        }
    }
    
    private fun showError(project: Project, message: String) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "Keeper Authentication Error")
        }
    }
    
    private fun showSetupError(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(
                project,
                "Failed to setup Keeper authentication.\n\n" +
                "Please try one of these manual steps:\n\n" +
                "For Biometric Authentication:\n" +
                "1. Run 'keeper shell' in terminal\n" +
                "2. Run 'biometric register'\n" +
                "3. Follow the prompts\n\n" +
                "For Persistent Login:\n" +
                "1. Run 'keeper shell' in terminal\n" +
                "2. Run 'this-device register'\n" +
                "3. Run 'this-device persistent-login on'\n\n" +
                "Then try this action again.",
                "Keeper Authentication Setup Failed"
            )
        }
    }
}