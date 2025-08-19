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
 * Persistent Keeper shell service with robust prompt detection
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
    
    // Command execution
    private val currentCommand = AtomicReference<CommandExecution?>(null)
    
    // Output buffer for continuous reading
    private val outputBuffer = StringBuilder()
    private val bufferLock = ReentrantLock()
    
    // Reader thread
    @Volatile private var readerThread: Thread? = null
    
    data class CommandExecution(
        val future: CompletableFuture<String>,
        val commandText: String,
        val startTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Start the persistent keeper shell
     */
    fun startShell(): Boolean {
        if (shellReady.get()) {
            logger.info("✅ Shell already running")
            return true
        }
        
        if (!starting.compareAndSet(false, true)) {
            return waitForStartupComplete()
        }
        
        try {
            logger.info("🚀 Starting persistent Keeper shell...")
            
            // Start keeper shell process
            process = ProcessBuilder("keeper", "shell")
                .redirectErrorStream(true)
                .start()
            
            writer = OutputStreamWriter(process!!.outputStream)
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            
            // Start the output reader thread
            startReaderThread()
            
            // Wait for shell to be ready using multiple strategies
            val success = waitForShellInitialization()
            
            if (success) {
                shellReady.set(true)
                logger.info("✅ Keeper shell ready! Can now execute commands.")
            } else {
                cleanup()
                logger.error("❌ Failed to initialize Keeper shell")
            }
            
            return success
            
        } catch (e: Exception) {
            logger.error("Failed to start Keeper shell", e)
            cleanup()
            return false
        } finally {
            starting.set(false)
        }
    }
    
    private fun startReaderThread() {
        readerThread = Thread({
            logger.info("📖 Reader thread started")
            
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
                logger.info("📖 Reader thread stopped")
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
            line.contains("urllib3") -> logger.debug("📥 Warning: $line")
            line.contains("#") && line.length > 50 -> logger.debug("📥 Banner: ASCII art")
            line.contains("Keeper") && line.contains("Commander") -> logger.info("📥 Banner: $line")
            line.contains("version") -> logger.info("📥 Version: $line")
            line.contains("Logging in") -> logger.info("📥 Auth: $line")
            line.contains("Successfully authenticated") -> logger.info("📥 Auth: $line")
            line.contains("Syncing") -> logger.info("📥 Sync: $line")
            line.contains("Decrypted") -> logger.info("📥 Sync: $line")
            line.contains("breachwatch") -> logger.debug("📥 Info: $line")
            line.trim().isNotEmpty() -> logger.debug("📥 Output: $line")
        }
    }
    
    private fun isShellReady(text: String): Boolean {
        // Multiple ways to detect that shell is ready
        return text.contains("My Vault>") || 
               text.contains("Keeper>") ||
               (text.contains("Decrypted") && text.contains("record(s)")) ||
               (text.contains("Successfully authenticated") && 
                text.contains("Syncing") && 
                text.contains("Decrypted"))
    }
    
    private fun isCommandComplete(text: String): Boolean {
        return text.contains("My Vault>") || text.contains("Keeper>")
    }
    
    private fun handleShellReady() {
        if (!shellReady.get()) {
            logger.info("🎯 Shell ready detected!")
            
            // Send a test command to trigger the prompt
            try {
                writer?.apply {
                    write("\n")  // Send empty line to trigger prompt
                    flush()
                }
                logger.info("📤 Sent newline to trigger prompt")
            } catch (e: Exception) {
                logger.debug("Error sending newline", e)
            }
        }
    }
    
    private fun handleCommandComplete(execution: CommandExecution, fullOutput: String) {
        val result = extractCommandOutput(fullOutput, execution.commandText)
        
        logger.info("✅ Command '${execution.commandText}' completed in ${System.currentTimeMillis() - execution.startTime}ms")
        logger.debug("📤 Command result (${result.length} chars): ${result.take(200)}${if (result.length > 200) "..." else ""}")
        
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
                !line.contains("My Vault>") && !line.contains("Keeper>")
            }.joinToString("\n").trim()
        }
        
        // Return everything after the command line, except prompt lines
        return lines.drop(commandLineIndex + 1)
            .filter { line ->
                !line.contains("My Vault>") && !line.contains("Keeper>")
            }
            .joinToString("\n").trim()
    }
    
    private fun waitForShellInitialization(): Boolean {
        val startTime = System.currentTimeMillis()
        val timeoutMs = 45_000L // 45 seconds for initial startup
        
        logger.info("⏳ Waiting for Keeper shell to be ready (up to 45 seconds)...")
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            bufferLock.withLock {
                val currentOutput = outputBuffer.toString()
                
                if (isShellReady(currentOutput)) {
                    logger.info("🎯 Shell appears ready based on output content")
                    
                    // Try to get a prompt by sending a newline
                    try {
                        writer?.apply {
                            write("\n")
                            flush()
                        }
                        Thread.sleep(1000) // Wait for prompt response
                        
                        // Check if we got a prompt after the newline
                        val updatedOutput = outputBuffer.toString()
                        if (updatedOutput.contains("My Vault>") || updatedOutput.contains("Keeper>")) {
                            logger.info("🎯 Confirmed: Got prompt after sending newline")
                            outputBuffer.setLength(0) // Clear startup output
                            return true
                        } else {
                            logger.info("🎯 No prompt yet, but shell seems ready. Assuming it's working.")
                            outputBuffer.setLength(0) // Clear startup output  
                            return true
                        }
                    } catch (e: Exception) {
                        logger.warn("Error sending test newline", e)
                    }
                }
                
                // Show progress every 5 seconds
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed % 5000 < 200) {
                    val lastLines = currentOutput.lines().takeLast(3).joinToString(" | ")
                    logger.info("⏳ Still waiting (${elapsed/1000}s)... Recent: $lastLines")
                }
            }
            
            // Check if process died
            if (process?.isAlive != true) {
                logger.error("❌ Keeper shell process died during startup")
                return false
            }
            
            Thread.sleep(200)
        }
        
        // Timeout - show diagnostic info
        bufferLock.withLock {
            val output = outputBuffer.toString()
            logger.error("❌ Timeout waiting for shell ready after ${timeoutMs}ms")
            logger.error("📋 Process alive: ${process?.isAlive}")
            logger.error("📋 Full output: '$output'")
        }
        
        return false
    }
    
    /**
     * Execute a command in the persistent shell
     */
    fun executeCommand(command: String, timeoutSeconds: Long = 30): String {
        commandLock.withLock {
            // Ensure shell is running
            if (!ensureShellRunning()) {
                throw IllegalStateException("Could not start Keeper shell")
            }
            
            // Create command execution context
            val execution = CommandExecution(CompletableFuture(), command)
            currentCommand.set(execution)
            
            try {
                logger.info("🔄 Executing command: $command")
                
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
        logger.info("🛑 Stopping persistent Keeper shell...")
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
    
    private fun ensureShellRunning(): Boolean {
        return if (isReady()) {
            true
        } else {
            logger.info("Shell not ready, starting...")
            startShell()
        }
    }
    
    private fun waitForStartupComplete(): Boolean {
        var attempts = 0
        while (starting.get() && attempts < 450) { // 45 seconds
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
        
        logger.info("🧹 Cleanup completed")
    }
}