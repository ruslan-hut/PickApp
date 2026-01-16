package ua.com.programmer.pick.presentation.documents

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import ua.com.programmer.pick.R

@Composable
fun DocumentsScreen(
    modifier: Modifier = Modifier,
    viewModel: DocumentsViewModel = hiltViewModel(),
    onNavigate: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDocuments()
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else {
            uiState.errorMessage?.let { errKey ->
                val errText = if (errKey == DocumentsViewModel.ERROR_LOADING_DOCUMENTS) stringResource(R.string.error_loading_documents) else errKey
                Text(text = errText, color = MaterialTheme.colorScheme.error)
            }

            LazyColumn {
                items(uiState.documents, key = { it.id }) { doc ->
                    DocumentListItem(document = doc, onClick = { id -> viewModel.onDocumentClick(id, onNavigate) })
                }
            }
        }
    }
}
