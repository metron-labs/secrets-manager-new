package keepersecurity.action

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Data class representing a secret retrieved from Keeper Vault
 */
data class KeeperSecret(
    val uid: String,
    val title: String,
    val login: String?,
    val password: String?,
    val url: String?,
    val notes: String?
)

/**
 * Exception thrown when Keeper operations fail
 */
class KeeperVaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Service for interacting with Keeper Vault using Keeper Commander CLI
 */
@Service(Service.Level.PROJECT)
class KeeperVaultService(private val project: Project) {
    
    private val logger = thisLogger()
    
    init {
        logger.info("KeeperVaultService initialized for project: ${project.name}")
    }
    
    /**
     * Checks if Keeper Commander CLI is available on the system
     */
    suspend fun isKeeperCommanderAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("keeper", "--version").start()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            logger.warn("Keeper Commander CLI not found: ${e.message}")
            false
        }
    }
    
    /**
     * Executes a Keeper Commander CLI command and returns the output
     */
    private suspend fun executeKeeperCommand(vararg args: String): String = withContext(Dispatchers.IO) {
        suspendCoroutine { continuation ->
            try {
                val command = listOf("keeper") + args.toList()
                logger.info("Executing command: ${command.joinToString(" ")}")
                
                val processBuilder = ProcessBuilder(command)
                val process = processBuilder.start()
                
                val output = StringBuilder()
                val errorOutput = StringBuilder()
                
                // Read stdout
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
                
                // Read stderr
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))
                while (errorReader.readLine().also { line = it } != null) {
                    errorOutput.appendLine(line)
                }
                
                val exitCode = process.waitFor()
                
                if (exitCode != 0) {
                    val errorMsg = "Keeper command failed with exit code $exitCode: ${errorOutput.toString().trim()}"
                    logger.error(errorMsg)
                    continuation.resumeWithException(KeeperVaultException(errorMsg))
                } else {
                    continuation.resume(output.toString().trim())
                }
                
            } catch (e: Exception) {
                val errorMsg = "Failed to execute Keeper command: ${e.message}"
                logger.error(errorMsg, e)
                continuation.resumeWithException(KeeperVaultException(errorMsg, e))
            }
        }
    }
    
    /**
     * Fetches a secret from Keeper Vault by record UID
     */
    suspend fun getSecretByUid(recordUid: String): KeeperSecret? {
        try {
            logger.info("Fetching secret for UID: $recordUid")
            
            // First, check if we're logged in
            if (!isLoggedIn()) {
                throw KeeperVaultException("Not logged in to Keeper. Please run 'keeper login' first.")
            }
            
            // Get the record details in JSON format
            val output = executeKeeperCommand("get", recordUid, "--format=json")
            
            // Parse the JSON output (simplified parsing - you might want to use a proper JSON library)
            return parseKeeperRecord(recordUid, output)
            
        } catch (e: KeeperVaultException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Failed to fetch secret by UID '$recordUid': ${e.message}"
            logger.error(errorMsg, e)
            throw KeeperVaultException(errorMsg, e)
        }
    }
    
    /**
     * Searches for secrets by title
     */
    suspend fun searchSecretsByTitle(title: String): List<KeeperSecret> {
        try {
            logger.info("Searching for secrets with title: $title")
            
            if (!isLoggedIn()) {
                throw KeeperVaultException("Not logged in to Keeper. Please run 'keeper login' first.")
            }
            
            // Search for records containing the title
            val output = executeKeeperCommand("search", title, "--format=json")
            
            return parseKeeperSearchResults(output)
            
        } catch (e: KeeperVaultException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Failed to search secrets by title '$title': ${e.message}"
            logger.error(errorMsg, e)
            throw KeeperVaultException(errorMsg, e)
        }
    }
    
    /**
     * Lists all available records
     */
    suspend fun listAllSecrets(): List<KeeperSecret> {
        try {
            logger.info("Listing all secrets")
            
            if (!isLoggedIn()) {
                throw KeeperVaultException("Not logged in to Keeper. Please run 'keeper login' first.")
            }
            
            val output = executeKeeperCommand("list", "--format=json")
            return parseKeeperSearchResults(output)
            
        } catch (e: KeeperVaultException) {
            throw e
        } catch (e: Exception) {
            val errorMsg = "Failed to list all secrets: ${e.message}"
            logger.error(errorMsg, e)
            throw KeeperVaultException(errorMsg, e)
        }
    }
    
    /**
     * Checks if the user is logged in to Keeper
     */
    private suspend fun isLoggedIn(): Boolean {
        return try {
            executeKeeperCommand("whoami")
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Simple JSON-like parsing for Keeper record (you should use a proper JSON library like kotlinx.serialization)
     */
    private fun parseKeeperRecord(uid: String, jsonOutput: String): KeeperSecret? {
        // This is a simplified parser - in a real implementation, use kotlinx.serialization or gson
        try {
            val lines = jsonOutput.lines()
            var title = ""
            var login: String? = null
            var password: String? = null
            var url: String? = null
            var notes: String? = null
            
            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.contains("\"title\"") -> {
                        title = extractJsonValue(trimmed)
                    }
                    trimmed.contains("\"login\"") -> {
                        login = extractJsonValue(trimmed)
                    }
                    trimmed.contains("\"password\"") -> {
                        password = extractJsonValue(trimmed)
                    }
                    trimmed.contains("\"url\"") -> {
                        url = extractJsonValue(trimmed)
                    }
                    trimmed.contains("\"notes\"") -> {
                        notes = extractJsonValue(trimmed)
                    }
                }
            }
            
            return KeeperSecret(uid, title, login, password, url, notes)
            
        } catch (e: Exception) {
            logger.error("Failed to parse Keeper record: ${e.message}")
            return null
        }
    }
    
    /**
     * Parse search results (simplified implementation)
     */
    private fun parseKeeperSearchResults(jsonOutput: String): List<KeeperSecret> {
        // This is a simplified parser - in a real implementation, use kotlinx.serialization or gson
        val secrets = mutableListOf<KeeperSecret>()
        
        try {
            // Split by records and parse each one
            val records = jsonOutput.split("}, {")
            
            for (record in records) {
                val lines = record.lines()
                var uid = ""
                var title = ""
                var login: String? = null
                var password: String? = null
                var url: String? = null
                var notes: String? = null
                
                for (line in lines) {
                    val trimmed = line.trim()
                    when {
                        trimmed.contains("\"record_uid\"") || trimmed.contains("\"uid\"") -> {
                            uid = extractJsonValue(trimmed)
                        }
                        trimmed.contains("\"title\"") -> {
                            title = extractJsonValue(trimmed)
                        }
                        trimmed.contains("\"login\"") -> {
                            login = extractJsonValue(trimmed)
                        }
                        trimmed.contains("\"password\"") -> {
                            password = extractJsonValue(trimmed)
                        }
                        trimmed.contains("\"url\"") -> {
                            url = extractJsonValue(trimmed)
                        }
                        trimmed.contains("\"notes\"") -> {
                            notes = extractJsonValue(trimmed)
                        }
                    }
                }
                
                if (uid.isNotEmpty() && title.isNotEmpty()) {
                    secrets.add(KeeperSecret(uid, title, login, password, url, notes))
                }
            }
            
        } catch (e: Exception) {
            logger.error("Failed to parse Keeper search results: ${e.message}")
        }
        
        return secrets
    }
    
    /**
     * Extract value from a JSON line (simplified implementation)
     */
    private fun extractJsonValue(line: String): String {
        val colonIndex = line.indexOf(":")
        if (colonIndex == -1) return ""
        
        val value = line.substring(colonIndex + 1).trim()
        return value.removeSurrounding("\"").removeSuffix(",")
    }
    
    /**
     * Login to Keeper Vault
     */
    suspend fun login(email: String? = null): Boolean {
        return try {
            val args = if (email != null) {
                arrayOf("login", email)
            } else {
                arrayOf("login")
            }
            
            executeKeeperCommand(*args)
            logger.info("Successfully logged in to Keeper")
            true
        } catch (e: Exception) {
            logger.error("Failed to login to Keeper: ${e.message}")
            false
        }
    }
    
    /**
     * Logout from Keeper Vault
     */
    suspend fun logout(): Boolean {
        return try {
            executeKeeperCommand("logout")
            logger.info("Successfully logged out from Keeper")
            true
        } catch (e: Exception) {
            logger.error("Failed to logout from Keeper: ${e.message}")
            false
        }
    }
} 