package keepersecurity.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Application-level service that manages the Keeper shell lifecycle
 */
@Service
class KeeperPluginService {
    private val logger = thisLogger()
    
    init {
        logger.info("KeeperPluginService initialized - starting background shell")
        // Start the shell service in background
        Thread({
            try {
                KeeperShellService.startShell()
                logger.info("Keeper shell service started successfully")
            } catch (e: Exception) {
                logger.warn("Failed to start Keeper shell service on plugin load", e)
            }
        }, "KeeperShell-Startup").apply { 
            isDaemon = true 
            start()
        }
    }
    
    fun ensureShellReady(): Boolean {
        return if (KeeperShellService.isReady()) {
            true
        } else {
            logger.info("Shell not ready, starting...")
            KeeperShellService.startShell()
        }
    }
}

/**
 * Startup activity to initialize the Keeper shell service
 */
class KeeperStartupActivity : ProjectActivity {
    private val logger = thisLogger()
    
    override suspend fun execute(project: Project) {
        logger.info("Initializing Keeper shell service for project: ${project.name}")
        // Get the service instance to trigger initialization
        com.intellij.openapi.components.service<KeeperPluginService>()
    }
}