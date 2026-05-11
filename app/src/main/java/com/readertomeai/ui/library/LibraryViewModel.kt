package com.readertomeai.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readertomeai.ReaderToMeApp
import com.readertomeai.data.model.Book
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LibraryViewModel : ViewModel() {

    private val repo = ReaderToMeApp.instance.bookRepository
    private val settings = ReaderToMeApp.instance.settingsRepository

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError

    val sortOrder = settings.sortOrder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "recent")
    val isGridView = settings.gridView.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val books: StateFlow<List<Book>> = combine(sortOrder, _searchQuery) { sort, query ->
        Pair(sort, query)
    }.flatMapLatest { (sort, query) ->
        if (query.isNotBlank()) {
            repo.searchBooks(query)
        } else {
            when (sort) {
                "title" -> repo.getBooksByTitle()
                "author" -> repo.getBooksByAuthor()
                "added" -> repo.getBooksByAdded()
                else -> repo.getBooksByRecent()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortOrder(order: String) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun toggleViewMode() {
        viewModelScope.launch {
            val current = isGridView.value
            settings.setGridView(!current)
        }
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            try {
                val book = repo.importBook(uri)
                if (book == null) {
                    _importError.value = "Could not import this file. Make sure it's a valid ePub."
                }
            } catch (e: Exception) {
                _importError.value = e.message ?: "Import failed"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repo.deleteBook(book)
        }
    }

    fun clearError() {
        _importError.value = null
    }
}
