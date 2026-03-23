package ua.com.programmer.pick.presentation.documents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.hiltViewModel
import ua.com.programmer.pick.R
import ua.com.programmer.pick.presentation.common.EmptyState
import ua.com.programmer.pick.presentation.common.PickAppBar
import ua.com.programmer.pick.presentation.common.SearchBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
    onNavigateBack: (() -> Unit)? = null,
    onNavigate: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    LifecycleResumeEffect(Unit) {
        viewModel.onRefresh()
        onPauseOrDispose { }
    }
    var searchQuery by remember { mutableStateOf("") }

    val filteredDocuments = remember(uiState.documents, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.documents
        } else {
            uiState.documents.filter { doc ->
                doc.number.contains(searchQuery, ignoreCase = true) ||
                        doc.clientName?.contains(searchQuery, ignoreCase = true) == true
            }
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PickAppBar(
                title = stringResource(R.string.documents),
                onNavigateBack = onNavigateBack,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth()
            )

            // Content
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = viewModel::onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(48.dp)
                                .align(Alignment.Center)
                        )
                    }

                    uiState.errorMessage != null -> {
                        val errKey = uiState.errorMessage
                        val errText = if (errKey == DocumentsViewModel.ERROR_LOADING_DOCUMENTS) {
                            stringResource(R.string.error_loading_documents)
                        } else {
                            errKey ?: ""
                        }
                        Text(
                            text = errText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp)
                        )
                    }

                    filteredDocuments.isEmpty() -> {
                        EmptyState(
                            message = stringResource(R.string.no_documents_description),
                            title = stringResource(R.string.no_documents),
                            icon = R.drawable.outline_description_24
                        )
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(
                                items = filteredDocuments,
                                key = { it.id }
                            ) { doc ->
                                DocumentListItem(
                                    document = doc,
                                    onClick = { id -> viewModel.onDocumentClick(id, onNavigate) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
