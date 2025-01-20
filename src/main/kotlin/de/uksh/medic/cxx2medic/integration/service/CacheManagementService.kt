package de.uksh.medic.cxx2medic.integration.service

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Service

@Service
class CacheManagementService(
    @Autowired private val cacheManager: CacheManager
)
{
    fun clearAllCaches()
    {
        logger.info("Clearing all caches")
        cacheManager.cacheNames.forEach { cacheManager.getCache(it)?.clear() }
    }

    companion object
    {
        private val logger: Logger = LogManager.getLogger(this::class)
    }
}