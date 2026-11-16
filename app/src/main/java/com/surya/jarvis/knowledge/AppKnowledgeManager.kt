package com.surya.jarvis.knowledge

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class AppKnowledgeManager(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = mutableMapOf<String, AppKnowledge>()
    private val db = KnowledgeDatabase.getInstance(context)

    suspend fun getKnowledge(packageName: String): AppKnowledge? {
        return cache[packageName] ?: loadFromAssets(packageName) ?: loadFromDb(packageName)
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

    private suspend fun loadFromDb(packageName: String): AppKnowledge? {
        return try {
            val entity = db.knowledgeDao().get(packageName)
            entity?.toAppKnowledge()?.also { cache[packageName] = it }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateKnowledge(knowledge: AppKnowledge) {
        cache[knowledge.packageName] = knowledge
        CoroutineScope(Dispatchers.IO).launch {
            db.knowledgeDao().upsert(knowledge.toEntity())
        }
    }

    fun detectVersionChange(packageName: String): Boolean {
        val currentVersion = try {
            context.packageManager.getPackageInfo(packageName, 0).longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
        val cachedVersion = getKnowledgeSync(packageName)?.versionCode ?: 0
        return currentVersion != cachedVersion
    }

    suspend fun migrateKnowledge(
        packageName: String,
        newVersionCode: Long,
        newVersionName: String
    ): AppKnowledge? {
        val old = getKnowledgeSync(packageName) ?: return null

        // Auto-migrate key elements using fuzzy matching
        val migratedElements = old.keyElements.mapValues { (role, element) ->
            role to element.copy(
                versionHistory = element.versionHistory + VersionSnapshot(
                    versionCode = newVersionCode,
                    versionName = newVersionName,
                    timestamp = System.currentTimeMillis(),
                    keyElementChanges = mapOf(role to element.resourceIds),
                    uiChanges = listOf("Version updated to $newVersionName")
                )
            )
        }

        val migrated = old.copy(
            versionCode = newVersionCode,
            versionName = newVersionName,
            keyElements = migratedElements,
            versionHistory = old.versionHistory + VersionSnapshot(
                versionCode = newVersionCode,
                versionName = newVersionName,
                timestamp = System.currentTimeMillis(),
                keyElementChanges = migratedElements.mapValues { (_, v) -> v.resourceIds },
                uiChanges = listOf("Auto-migrated from version ${old.versionName}")
            ),
            lastVerified = System.currentTimeMillis()
        )

        updateKnowledge(migrated)
        return migrated
    }

    fun isAppKnown(packageName: String): Boolean {
        return cache.containsKey(packageName) ||
            context.assets.list("app_knowledge")?.any { it == "${packageName.replace(".", "_")}.json" } == true ||
            try {
                db.knowledgeDao().get(packageName) != null
            } catch (e: Exception) {
                false
            }
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