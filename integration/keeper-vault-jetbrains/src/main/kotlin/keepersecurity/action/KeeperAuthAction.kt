package keepersecurity.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import keepersecurity.service.KeeperShellService

class KeeperAuthAction : AnAction("Check Keeper Authorization") {
    private val logger = thisLogger()

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        object : Task.Backgroundable(project, "Checking Keeper Authentication...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    logger.info("=== KEEPER AUTH: Starting comprehensive authentication check ===")
                    
                    indicator.text = "Connecting to Keeper shell..."
                    
                    // Ensure shell is ready
                    if (!KeeperShellService.isReady()) {
                        indicator.text = "Starting Keeper shell..."
                        if (!KeeperShellService.startShell()) {
                            showError(project, "❌ Failed to start Keeper shell. Please check your Keeper CLI installation.")
                            return
                        }
                    }
                    
                    // Step 1: Check if we can access the vault at all
                    indicator.text = "Checking vault access..."
                    if (!checkBasicAccess()) {
                        showError(project, "❌ Cannot access Keeper vault. Please run 'keeper login' in terminal first.")
                        return
                    }
                    
                    // Step 2: Try biometric authentication first
                    indicator.text = "Checking biometric authentication..."
                    val biometricResult = checkBiometricAuth()
                    
                    when (biometricResult) {
                        BiometricStatus.SUCCESS -> {
                            logger.info("✅ Biometric authentication successful")
                            showSuccess(project, "✅ Keeper Authentication Successful!\n\n🔐 Biometric authentication is working correctly.\n\n⚡ All actions will use the persistent shell for maximum speed!")
                            return
                        }
                        BiometricStatus.NOT_REGISTERED -> {
                            logger.info("⚠️ Biometric not registered, attempting to register...")
                            indicator.text = "Setting up biometric authentication..."
                            
                            if (setupBiometric()) {
                                logger.info("✅ Biometric setup successful")
                                showSuccess(project, "✅ Keeper Authentication Setup Complete!\n\n🔐 Biometric authentication has been configured and is working.\n\n⚡ All actions will use the persistent shell for maximum speed!")
                                return
                            } else {
                                logger.warn("⚠️ Biometric setup failed, falling back to persistent login")
                                indicator.text = "Biometric setup failed, checking persistent login..."
                            }
                        }
                        BiometricStatus.NOT_SUPPORTED -> {
                            logger.info("ℹ️ Biometric not supported on this device, checking persistent login")
                            indicator.text = "Biometric not supported, checking persistent login..."
                        }
                        BiometricStatus.FAILED -> {
                            logger.warn("⚠️ Biometric verification failed, falling back to persistent login")
                            indicator.text = "Biometric failed, checking persistent login..."
                        }
                    }
                    
                    // Step 3: Fall back to persistent login
                    indicator.text = "Checking persistent login..."
                    val persistentResult = checkPersistentLogin()
                    
                    when (persistentResult) {
                        PersistentStatus.SUCCESS -> {
                            logger.info("✅ Persistent login successful")
                            showSuccess(project, "✅ Keeper Authentication Successful!\n\n🔑 Persistent login is enabled and working.\n\nNote: Consider enabling biometric authentication for enhanced security.\n\n⚡ All actions will use the persistent shell for maximum speed!")
                            return
                        }
                        PersistentStatus.NOT_ENABLED -> {
                            logger.info("⚠️ Persistent login not enabled, attempting to enable...")
                            indicator.text = "Setting up persistent login..."
                            
                            if (setupPersistentLogin()) {
                                logger.info("✅ Persistent login setup successful")
                                showSuccess(project, "✅ Keeper Authentication Setup Complete!\n\n🔑 Persistent login has been enabled and is working.\n\nNote: Consider enabling biometric authentication for enhanced security.\n\n⚡ All actions will use the persistent shell for maximum speed!")
                                return
                            } else {
                                logger.error("❌ Failed to setup persistent login")
                                showSetupError(project)
                                return
                            }
                        }
                        PersistentStatus.FAILED -> {
                            logger.error("❌ Persistent login check failed")
                            showSetupError(project)
                            return
                        }
                    }
                    
                } catch (ex: Exception) {
                    logger.error("=== KEEPER AUTH: Exception during comprehensive check ===", ex)
                    showError(project, "❌ Unexpected error during authentication check: ${ex.message}")
                }
            }
        }.queue()
    }
    
    private enum class BiometricStatus {
        SUCCESS, NOT_REGISTERED, NOT_SUPPORTED, FAILED
    }
    
    private enum class PersistentStatus {
        SUCCESS, NOT_ENABLED, FAILED
    }
    
    private fun checkBasicAccess(): Boolean {
        return try {
            val output = KeeperShellService.executeCommand("list", 10)
            logger.info("Basic access check - output length: ${output.length}")
            !output.contains("not logged in", ignoreCase = true) && 
            !output.contains("login required", ignoreCase = true)
        } catch (ex: Exception) {
            logger.error("Basic access check failed", ex)
            false
        }
    }
    
    private fun checkBiometricAuth(): BiometricStatus {
        return try {
            logger.info("🔐 Checking biometric authentication...")
            val output = KeeperShellService.executeCommand("biometric verify", 15)
            logger.info("Biometric verify output: $output")
            
            when {
                output.contains("Status: SUCCESSFUL", ignoreCase = true) -> {
                    logger.info("✅ Biometric verification successful")
                    BiometricStatus.SUCCESS
                }
                output.contains("not registered", ignoreCase = true) || 
                output.contains("no biometric", ignoreCase = true) -> {
                    logger.info("⚠️ Biometric not registered")
                    BiometricStatus.NOT_REGISTERED
                }
                output.contains("not supported", ignoreCase = true) ||
                output.contains("not available", ignoreCase = true) -> {
                    logger.info("ℹ️ Biometric not supported on this device")
                    BiometricStatus.NOT_SUPPORTED
                }
                else -> {
                    logger.warn("⚠️ Biometric verification failed or unknown response")
                    BiometricStatus.FAILED
                }
            }
        } catch (ex: Exception) {
            logger.error("Biometric check failed", ex)
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
            logger.info("🔑 Checking persistent login status...")
            val output = KeeperShellService.executeCommand("this-device", 10)
            logger.info("This-device output: $output")
            
            when {
                output.contains("Persistent Login: ON", ignoreCase = true) ||
                output.contains("Persistent Login:\\s*ON".toRegex()) -> {
                    logger.info("✅ Persistent login is enabled")
                    PersistentStatus.SUCCESS
                }
                output.contains("Persistent Login: OFF", ignoreCase = true) ||
                output.contains("Persistent Login:\\s*OFF".toRegex()) -> {
                    logger.info("⚠️ Persistent login is disabled")
                    PersistentStatus.NOT_ENABLED
                }
                else -> {
                    logger.warn("⚠️ Could not determine persistent login status")
                    PersistentStatus.FAILED
                }
            }
        } catch (ex: Exception) {
            logger.error("Persistent login check failed", ex)
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
                "❌ Failed to setup Keeper authentication.\n\n" +
                "Please try one of these manual steps:\n\n" +
                "🔐 For Biometric Authentication:\n" +
                "1. Run 'keeper shell' in terminal\n" +
                "2. Run 'biometric register'\n" +
                "3. Follow the prompts\n\n" +
                "🔑 For Persistent Login:\n" +
                "1. Run 'keeper shell' in terminal\n" +
                "2. Run 'this-device register'\n" +
                "3. Run 'this-device persistent-login on'\n\n" +
                "Then try this action again.",
                "Keeper Authentication Setup Failed"
            )
        }
    }
}