package br.com.docscan.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import br.com.docscan.domain.Document
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents", indices = [Index(value = ["nameKey"], unique = true)])
internal data class DocumentRow(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val revision: String,
) {
    fun toDocument() = Document(id, name, createdAt, updatedAt, pageCount, "$id/thumb.jpg")
}

@Entity(tableName = "completed_migrations")
internal data class CompletedMigration(@PrimaryKey val key: String)

@Dao
internal interface DocumentDao {
    @Query("SELECT * FROM documents WHERE instr(nameKey, :query) > 0")
    fun observe(query: String): Flow<List<DocumentRow>>

    @Query("SELECT * FROM documents")
    suspend fun all(): List<DocumentRow>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun get(id: String): DocumentRow?

    @Upsert
    suspend fun upsert(document: DocumentRow)

    @Query("UPDATE documents SET name = :name, nameKey = :nameKey, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, nameKey: String, updatedAt: Long)

    @Query("DELETE FROM documents WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT EXISTS(SELECT 1 FROM completed_migrations WHERE `key` = :key)")
    suspend fun migrated(key: String): Boolean

    @Insert
    suspend fun completeMigration(migration: CompletedMigration)
}

@Database(entities = [DocumentRow::class, CompletedMigration::class], version = 1)
internal abstract class DocumentDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
}
