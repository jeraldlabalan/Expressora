package com.example.expressora.recognition.persistence

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Serializer for SequenceHistoryStore protobuf
 */
object SequenceHistorySerializer : Serializer<SequenceHistoryStore> {
    override val defaultValue: SequenceHistoryStore = SequenceHistoryStore.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SequenceHistoryStore {
        try {
            return SequenceHistoryStore.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", exception)
        }
    }

    override suspend fun writeTo(t: SequenceHistoryStore, output: OutputStream) = t.writeTo(output)
}

/**
 * DataStore extension property
 */
private val Context.sequenceHistoryDataStore: DataStore<SequenceHistoryStore> by dataStore(
    fileName = "sequence_history.pb",
    serializer = SequenceHistorySerializer
)

/**
 * Repository for managing sequence history with Proto DataStore
 */
class SequenceHistoryRepository(private val context: Context) {
    private val TAG = "SequenceHistoryRepo"
    private val dataStore = context.sequenceHistoryDataStore
    private val maxRecords = 20
    
    /**
     * Get history as a flow
     */
    fun getHistory(): Flow<List<SequenceRecord>> {
        return dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    Log.e(TAG, "Error reading history", exception)
                    emit(SequenceHistoryStore.getDefaultInstance())
                } else {
                    throw exception
                }
            }
            .map { store -> store.recordsList }
    }
    
    /**
     * Save a new sequence (keeps last 20)
     */
    suspend fun saveSequence(tokens: List<String>, origin: String? = null) {
        try {
            dataStore.updateData { currentStore ->
                val newRecord = SequenceRecord.newBuilder()
                    .addAllTokens(tokens)
                    .setTimestamp(System.currentTimeMillis())
                    .setOrigin(origin ?: "UNKNOWN")
                    .build()
                
                val updatedRecords = currentStore.recordsList + newRecord
                
                // Keep only last 20
                val finalRecords = if (updatedRecords.size > maxRecords) {
                    updatedRecords.takeLast(maxRecords)
                } else {
                    updatedRecords
                }
                
                SequenceHistoryStore.newBuilder()
                    .addAllRecords(finalRecords)
                    .build()
            }
            
            Log.i(TAG, "Saved sequence: $tokens")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save sequence", e)
        }
    }
    
    /**
     * Clear all history
     */
    suspend fun clearHistory() {
        try {
            dataStore.updateData {
                SequenceHistoryStore.getDefaultInstance()
            }
            Log.i(TAG, "Cleared history")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to clear history", e)
        }
    }
    
    /**
     * Get last N sequences
     */
    fun getLastN(n: Int): Flow<List<SequenceRecord>> {
        return getHistory().map { records ->
            records.takeLast(n)
        }
    }
    
    /**
     * Export last sequence as JSON
     */
    suspend fun exportLastSequenceAsJson(): String? {
        var lastRecord: SequenceRecord? = null
        dataStore.data.collect { store ->
            lastRecord = store.recordsList.lastOrNull()
        }
        
        return lastRecord?.let { record ->
            """
            {
              "tokens": ${record.tokensList.joinToString(prefix = "[\"", postfix = "\"]", separator = "\", \"")},
              "timestamp": ${record.timestamp},
              "origin": "${record.origin}"
            }
            """.trimIndent()
        }
    }
}

