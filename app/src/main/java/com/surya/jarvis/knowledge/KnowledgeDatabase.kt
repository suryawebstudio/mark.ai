package com.surya.jarvis.knowledge

import android.content.Context
import androidx.room.Database
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

@Entity(tableName = "app_knowledge")
data class AppKnowledgeEntity(
    @PrimaryKey val packageName: String,
    val jsonData: String,
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM app_knowledge WHERE packageName = :packageName")
    suspend fun get(packageName: String): AppKnowledgeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppKnowledgeEntity)

    @Query("SELECT * FROM app_knowledge")
    suspend fun getAll(): List<AppKnowledgeEntity>

    @Query("DELETE FROM app_knowledge WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Database(entities = [AppKnowledgeEntity::class], version = 1, exportSchema = false)
abstract class KnowledgeDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        @Volatile private var INSTANCE: KnowledgeDatabase? = null

        fun getInstance(context: Context): KnowledgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KnowledgeDatabase::class.java,
                    "mark_knowledge.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun AppKnowledgeEntity.toAppKnowledge(): AppKnowledge {
    return json.decodeFromString(AppKnowledge.serializer(), jsonData)
}

fun AppKnowledge.toEntity(): AppKnowledgeEntity {
    return AppKnowledgeEntity(packageName, json.encodeToString(this))
}