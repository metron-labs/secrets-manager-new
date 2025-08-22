package keepersecurity.service

import com.intellij.openapi.diagnostic.Logger
import java.io.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Persistent Keeper shell service with robust prompt detection, lazy loading, and OS-specific optimizations
 */
object KeeperShellService {
    private val logger = Logger.getInstance(KeeperShellService::class.java)
    
    // Process management
    @Volatile private var process: Process? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var reader: BufferedReader? = null
    
    // State management
    private val shellReady = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val commandLock = ReentrantLock()
    
    // Track if this is the first time starting (for better UX messaging)
    private val firstStart = AtomicBoolean(true)
    
    // Command execution
    private val currentCommand = AtomicReference<CommandExecution?>(null)
    
    // Output buffer for continuous reading
    private val outputBuffer = StringBuilder()
    private val bufferLock = ReentrantLock()
    
    // Reader thread
    @Volatile private var readerThread: Thread? = null
    
    // OS detection
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    private val isMacOS = System.getProperty("os.name").lowercase().contains("mac")
    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")
    
    data class CommandExecution(
        val future: CompletableFuture<String>,
        val commandText: String,
        val startTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Check if Keeper CLI is available on the system
     */
    private fun isKeeperCLIAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("keeper", "--version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            process.destroy()
            exitCode == 0
        } catch (e: Exception) {
            logger.warn("Keeper CLI availability check failed", e)
            false
        }
    }
    
    /**
     * Get detailed error message for missing Keeper CLI
     */
    private fun getKeeperCLIMissingMessage(): String {
        return if (isWindows) {
            """
            Keeper Commander CLI not found on Windows
            
            Please install it using one of these methods:
            
            1. Using pip (recommended):
               pip install keepercommander
            
            2. Using pip3:
               pip3 install keepercommander
            
            3. Using Python directly:
               python -m pip install keepercommander
            
            After installation:
            - Restart IntelliJ IDEA
            - Run 'keeper login' in Command Prompt
            - Authenticate with your Keeper account
            
            Make sure Python and pip are in your Windows PATH.
            """.trimIndent()
        } else {
            """
            Keeper Commander CLI not found
            
            Please install it using:
            pip install keepercommander
            
            Then run 'keeper login' to authenticate.
            """.trimIndent()
        }
    }
    
    /**
     * Get OS-specific timeout for shell initialization
     */
    private fun getShellInitTimeout(): Long {
        return when {
            isWindows -> 120_000L  // 2 minutes for Windows (slow auth + sync)
            isMacOS -> 45_000L     // 45 seconds for macOS (typically faster)
            isLinux -> 45_000L     // 45 seconds for Linux (typically faster)
            else -> 60_000L        // 1 minute default for unknown OS
        }
    }
    
    /**
     * Get OS-specific timeout description
     */
    private fun getTimeoutDescription(): String {
        return when {
            isWindows -> "up to 2 minutes on Windows (authentication + sync)"
            isMacOS -> "up to 45 seconds on macOS"
            isLinux -> "up to 45 seconds on Linux" 
            else -> "up to 1 minute"
        }
    }
    
    /**
     * Start the persistent keeper shell
     */
    fun startShell(): Boolean {
        if (shellReady.get()) {
            logger.debug("Shell already running")
            return true
        }
        
        if (!starting.compareAndSet(false, true)) {
            return waitForStartupComplete()
        }
        
        try {
            // Check if Keeper CLI is available first
            if (!isKeeperCLIAvailable()) {
                val errorMessage = getKeeperCLIMissingMessage()
                logger.error(errorMessage)
                throw RuntimeException(errorMessage)
            }
            
            // Better logging for first-time vs restart
            if (firstStart.get()) {
                logger.info("Starting Keeper shell on first use (user action triggered)")
                firstStart.set(false)
            } else {
                logger.info("Restarting Keeper shell")
            }
            
            // Start keeper shell process
            process = ProcessBuilder("keeper", "shell")
                .redirectErrorStream(true)
                .start()
            
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            
            // Start the output reader thread
            startReaderThread()
            
            // Wait for shell to be ready using OS-specific strategies
            val success = waitForShellInitialization()
            
            if (success) {
                shellReady.set(true)
                logger.info("Keeper shell ready! Can now execute commands.")
            } else {
                cleanup()
                logger.error("Failed to initialize Keeper shell")
            }
            
            return success
            
        } catch (e: IOException) {
            // Handle the specific "CreateProcess error=2" on Windows
            val errorMessage = when {
                e.message?.contains("CreateProcess error=2") == true -> getKeeperCLIMissingMessage()
                e.message?.contains("No such file or directory") == true -> getKeeperCLIMissingMessage()
                else -> "Failed to start Keeper shell: ${e.message}"
            }
            
            logger.error(errorMessage, e)
            cleanup()
            throw RuntimeException(errorMessage, e)
            
        } catch (e: Exception) {
            logger.error("Failed to start Keeper shell", e)
            cleanup()
            throw RuntimeException("Failed to start Keeper shell: ${e.message}", e)
        } finally {
            starting.set(false)
        }
    }
    
    private fun startReaderThread() {
        readerThread = Thread({
            logger.debug("Reader thread started")
            
            try {
                val buffer = CharArray(256)
                val lineBuffer = StringBuilder()
                
                while (process?.isAlive == true && !Thread.currentThread().isInterrupted) {
                    if (reader?.ready() == true) {
                        val charsRead = reader?.read(buffer) ?: -1
                        if (charsRead > 0) {
                            val chunk = String(buffer, 0, charsRead)
                            processOutputChunk(chunk, lineBuffer)
                        }
                    } else {
                        Thread.sleep(100)
                    }
                }
            } catch (e: InterruptedException) {
                logger.debug("Reader thread interrupted")
            } catch (e: Exception) {
                logger.warn("Reader thread error", e)
            } finally {
                logger.debug("Reader thread stopped")
            }
        }, "KeeperShell-Reader")
        
        readerThread?.isDaemon = true
        readerThread?.start()
    }
    
    private fun processOutputChunk(chunk: String, lineBuffer: StringBuilder) {
        bufferLock.withLock {
            outputBuffer.append(chunk)
            lineBuffer.append(chunk)
            
            // Process complete lines
            val lines = lineBuffer.toString().split('\n')
            if (lines.size > 1) {
                // Process all complete lines except the last (which might be partial)
                for (i in 0 until lines.size - 1) {
                    val line = lines[i].replace('\r', ' ').trim()
                    if (line.isNotEmpty()) {
                        processCompleteLine(line)
                    }
                }
                
                // Keep the last partial line in the buffer
                lineBuffer.clear()
                lineBuffer.append(lines.last())
            }
            
            // Check for prompt in the current buffer (including partial lines)
            val currentText = outputBuffer.toString()
            if (isShellReady(currentText)) {
                handleShellReady()
            }
            
            // Check for command completion
            currentCommand.get()?.let { execution ->
                if (isCommandComplete(currentText)) {
                    handleCommandComplete(execution, currentText)
                }
            }
        }
    }
    
    private fun processCompleteLine(line: String) {
        when {
            line.contains("urllib3") -> logger.debug("Warning: $line")
            line.contains("#") && line.length > 50 -> logger.debug("Banner: ASCII art")
            line.contains("Keeper") && line.contains("Commander") -> logger.info("Banner: $line")
            line.contains("version") -> logger.info("Version: $line")
            line.contains("Logging in") -> logger.info("Auth: Starting authentication...")
            line.contains("Successfully authenticated") -> {
                logger.info("Auth: $line")
                if (isWindows) logger.info("Windows: Authentication successful, preparing vault...")
            }
            line.contains("Syncing") -> {
                logger.info("Sync: $line")
                if (isWindows) logger.info("Windows: Syncing vault data...")
            }
            line.contains("Decrypted") -> {
                logger.info("Sync: $line")
                if (isWindows) logger.info("Windows: Vault sync complete!")
            }
            line.contains("My Vault>") -> logger.info("Shell ready: $line")
            line.contains("Not logged in>") -> logger.warn("Shell ready but not authenticated: $line")
            line.contains("breachwatch") -> logger.debug("Info: $line")
            line.trim().isNotEmpty() -> logger.debug("Output: $line")
        }
    }
    
    private fun isShellReady(text: String): Boolean {
        // Multiple ways to detect that shell is ready or progressing
        return text.contains("My Vault>") || 
               text.contains("Keeper>") ||
               text.contains("Not logged in>") ||
               // Key improvement: Recognize successful authentication even if still syncing
               text.contains("Successfully authenticated") ||
               // Also recognize when sync is complete
               (text.contains("Decrypted") && text.contains("record(s)")) ||
               // Full completion pattern
               (text.contains("Successfully authenticated") && 
                text.contains("Syncing") && 
                text.contains("Decrypted"))
    }
    
    private fun isCommandComplete(text: String): Boolean {
        return text.contains("My Vault>") || 
               text.contains("Keeper>") ||
               text.contains("Not logged in>")
    }
    
    private fun handleShellReady() {
        if (!shellReady.get()) {
            logger.info("Shell ready detected!")
            
            // Send a test command to trigger the prompt
            try {
                writer?.apply {
                    write("\n")  // Send empty line to trigger prompt
                    flush()
                }
                logger.debug("Sent newline to trigger prompt")
            } catch (e: Exception) {
                logger.debug("Error sending newline", e)
                showError(project, "Command failed: ${e.message}")
            }
        }
    }
    
    private fun handleCommandComplete(execution: CommandExecution, fullOutput: String) {
        val result = extractCommandOutput(fullOutput, execution.commandText)
        
        logger.info("Command '${execution.commandText}' completed in ${System.currentTimeMillis() - execution.startTime}ms")
        logger.debug("Command result (${result.length} chars): ${result.take(200)}${if (result.length > 200) "..." else ""}")
        
        execution.future.complete(result)
        
        // Clear the buffer for next command
        outputBuffer.setLength(0)
    }
    
    private fun extractCommandOutput(fullOutput: String, commandText: String): String {
        val lines = fullOutput.lines()
        
        // Find the line where we sent the command
        var commandLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].contains(commandText)) {
                commandLineIndex = i
                break
            }
        }
        
        if (commandLineIndex == -1) {
            // Command line not found, return everything except prompt lines
            return lines.filter { line ->
                !line.contains("My Vault>") && !line.contains("Keeper>") && !line.contains("Not logged in>")
            }.joinToString("\n").trim()
        }
        
        // Return everything after the command line, except prompt lines
        return lines.drop(commandLineIndex + 1)
            .filter { line ->
                !line.contains("My Vault>") && !line.contains("Keeper>") && !line.contains("Not logged in>")
            }
            .joinToString("\n").trim()
    }
    
    private fun waitForShellInitialization(): Boolean {
        val startTime = System.currentTimeMillis()
        val timeoutMs = getShellInitTimeout()
        
        logger.info("Waiting for Keeper shell to be ready (${getTimeoutDescription()})...")
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            bufferLock.withLock {
                val currentOutput = outputBuffer.toString()
                
                if (isShellReady(currentOutput)) {
                    logger.info("Shell appears ready based on output content")
                    
                    // Try to get a prompt by sending a newline
                    try {
                        writer?.apply {
                            write("\n")
                            flush()
                        }
                        Thread.sleep(1000) // Wait for prompt response
                        
                        // Check if we got a prompt after the newline
                        val updatedOutput = outputBuffer.toString()
                        if (updatedOutput.contains("My Vault>") || updatedOutput.contains("Keeper>") || updatedOutput.contains("Not logged in>")) {
                            logger.info("Confirmed: Got prompt after sending newline")
                            outputBuffer.setLength(0) // Clear startup output
                            return true
                        } else if (updatedOutput.contains("Successfully authenticated")) {
                            logger.info("Authentication successful, shell is ready even if still syncing")
                            outputBuffer.setLength(0) // Clear startup output  
                            return true
                        }
                    } catch (e: Exception) {
                        logger.warn("Error sending test newline", e)
                    }
                }
                
                // Show OS-specific progress messages
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed % 10_000L < 200) { // Every 10 seconds
                    val lastLines = currentOutput.lines().takeLast(3).joinToString(" | ")
                    when {
                        isWindows -> logger.info("Windows: Still waiting (${elapsed/1000}s)... Recent: $lastLines")
                        else -> logger.info("Still waiting (${elapsed/1000}s)... Recent: $lastLines")
                    }
                }
            }
            
            // Check if process died
            if (process?.isAlive != true) {
                logger.error("Keeper shell process died during startup")
                return false
            }
            
            Thread.sleep(200)
        }
        
        // Timeout - show diagnostic info
        bufferLock.withLock {
            val output = outputBuffer.toString()
            logger.error("Timeout waiting for shell ready after ${timeoutMs}ms")
            logger.error("Process alive: ${process?.isAlive}")
            logger.error("OS: ${System.getProperty("os.name")}")
            logger.error("Full output: '$output'")
            
            if (isWindows && output.contains("Logging in to Keeper Commander") && !output.contains("Successfully authenticated")) {
                logger.error("Windows: Keeper authentication is taking longer than expected. Check network connectivity.")
            }
        }
        
        return false
    }
    
    /**
     * Execute a command in the persistent shell
     */
    fun executeCommand(command: String, timeoutSeconds: Long = 30): String {
        commandLock.withLock {
            // Ensure shell is running - this is where lazy loading happens
            if (!ensureShellRunning()) {
                throw IllegalStateException("Could not start Keeper shell. Please ensure Keeper CLI is installed and you're authenticated.")
            }
            
            // Create command execution context
            val execution = CommandExecution(CompletableFuture(), command)
            currentCommand.set(execution)
            
            try {
                logger.info("Executing command: $command")
                
                // Clear buffer before sending command
                bufferLock.withLock {
                    outputBuffer.setLength(0)
                }
                
                // Send command
                writer?.apply {
                    write(command)
                    write("\n")
                    flush()
                }
                
                // Wait for completion
                val result = execution.future.get(timeoutSeconds, TimeUnit.SECONDS)
                
                return result.trim()
                
            } catch (e: Exception) {
                logger.error("Command execution failed: $command", e)
                throw RuntimeException("Command failed: ${e.message}", e)
            } finally {
                currentCommand.set(null)
            }
        }
    }
    
    /**
     * Check if shell is ready and responsive
     */
    fun isReady(): Boolean {
        return shellReady.get() && process?.isAlive == true
    }
    
    /**
     * Stop the shell gracefully
     */
    fun stopShell() {
        logger.info("Stopping persistent Keeper shell...")
        shellReady.set(false)
        
        try {
            writer?.apply {
                write("q\n")
                flush()
            }
            Thread.sleep(1000)
        } catch (e: Exception) {
            logger.debug("Error sending quit command", e)
        }
        
        cleanup()
    }
    
    // Private helper methods
    
    /**
     * Ensure shell is running - this is the key method for lazy loading
     */
    private fun ensureShellRunning(): Boolean {
        return if (isReady()) {
            logger.debug("Shell already ready")
            true
        } else {
            logger.info("Shell not ready, starting on-demand due to user action...")
            try {
                startShell()
            } catch (e: Exception) {
                logger.error("Failed to start shell", e)
                false
            }
        }
    }
    
    private fun waitForStartupComplete(): Boolean {
        val maxAttempts = if (isWindows) 1200 else 450 // 2 minutes for Windows, 45 seconds for others
        var attempts = 0
        while (starting.get() && attempts < maxAttempts) {
            Thread.sleep(100)
            attempts++
        }
        return shellReady.get()
    }
    
    private fun cleanup() {
        try {
            readerThread?.interrupt()
            writer?.close()
            reader?.close()
            process?.destroyForcibly()
        } catch (e: Exception) {
            logger.debug("Error during cleanup", e)
        }
        
        process = null
        writer = null
        reader = null
        readerThread = null
        shellReady.set(false)
        starting.set(false)
        
        bufferLock.withLock {
            outputBuffer.setLength(0)
        }
        
        logger.info("Cleanup completed")
    }

    /**
    * Get the last startup output for authentication detection
    */
    fun getLastStartupOutput(): String {
        return bufferLock.withLock {
            outputBuffer.toString()
        }
    }
}