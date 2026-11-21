package com.surya.jarvis.knowledge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.Json

class AppKnowledgeManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, AppKnowledge>()

    fun getKnowledge(packageName: String): AppKnowledge? {
        return cache[packageName] ?: loadFromAssets(packageName)
    }

    fun getKnowledgeSync(packageName: String): AppKnowledge? {
        return cache[packageName] ?: loadFromAssets(packageName)
    }

    private fun loadFromAssets(packageName: String): AppKnowledge? {
        val fileName = "app_knowledge/${packageName.replace(".", "_")}.json"
        return try {
            context.assets.open(fileName).use { input ->
                val text = input.bufferedReader().readText()
                json.decodeFromString(AppKnowledge.serializer(), text).also { cache[packageName] = it }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun updateKnowledge(knowledge: AppKnowledge) {
        cache[knowledge.packageName] = knowledge
    }

    @Suppress("DEPRECATION")
    fun detectVersionChange(packageName: String): Boolean {
        val currentVersion = try {
            context.packageManager.getPackageInfo(packageName, 0).let { info ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                else info.versionCode.toLong()
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val cachedVersion = getKnowledgeSync(packageName)?.versionCode ?: 0
        return currentVersion != cachedVersion
    }

    fun migrateKnowledge(
        packageName: String,
        newVersionCode: Long,
        newVersionName: String
    ): AppKnowledge? {
        val old = getKnowledgeSync(packageName) ?: return null
        val versionSnapshot = VersionSnapshot(
            versionCode = newVersionCode,
            versionName = newVersionName,
            timestamp = System.currentTimeMillis(),
            keyElementChanges = old.keyElements.mapValues { (_, element) ->
                element.resourceIds
            },
            uiChanges = listOf("Version updated to $newVersionName")
        )
        val migrated = old.copy(
            versionCode = newVersionCode,
            versionName = newVersionName,
            versionHistory = old.versionHistory + versionSnapshot,
            lastVerified = System.currentTimeMillis()
        )
        updateKnowledge(migrated)
        return migrated
    }

    fun isAppKnown(packageName: String): Boolean {
        return cache.containsKey(packageName) ||
            context.assets.list("app_knowledge")?.any { it == "${packageName.replace(".", "_")}.json" } == true
    }

    fun isUserApp(packageName: String): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}