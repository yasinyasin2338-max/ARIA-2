package com.orbisai.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local-first memory contract; persistent encrypted storage will implement this interface. */
data class MemoryItem(
    val id: String,
    val text: String,
    val agentId: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

interface MemoryStore {
    val items: Flow<List<MemoryItem>>
    suspend fun put(item: MemoryItem)
    suspend fun delete(id: String)
    suspend fun clear()
}

class InMemoryStore : MemoryStore {
    private val state = MutableStateFlow<List<MemoryItem>>(emptyList())
    override val items: Flow<List<MemoryItem>> = state.asStateFlow()

    override suspend fun put(item: MemoryItem) {
        state.value = state.value.filterNot { it.id == item.id } + item
    }

    override suspend fun delete(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun clear() {
        state.value = emptyList()
    }
}
