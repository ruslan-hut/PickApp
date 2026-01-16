package ua.com.programmer.pick.presentation.document

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
fun DocumentDetailScreen(
    modifier: Modifier = Modifier,
    documentId: String,
    viewModel: DocumentDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(documentId) {
        viewModel.load(documentId)
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else {
            uiState.errorMessage?.let { errKey ->
                val errText = if (errKey == DocumentDetailViewModel.ERROR_LOADING_DOCUMENT) stringResource(R.string.error_loading_document) else errKey
                Text(text = errText, color = MaterialTheme.colorScheme.error)
            }

            uiState.document?.let { _ ->
                LazyColumn {
                    items(uiState.lines, key = { it.id }) { line ->
                        DocumentLineRow(line = line, onQuantityChange = { lineId, qty -> viewModel.updateLineQuantity(lineId, qty) })
                    }
                }
            }
        }
    }
}
