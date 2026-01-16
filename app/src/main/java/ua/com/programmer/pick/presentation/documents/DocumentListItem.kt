package ua.com.programmer.pick.presentation.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.domain.model.Document
import ua.com.programmer.pick.R

@Composable
fun DocumentListItem(document: Document, onClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth().padding(8.dp).clickable { onClick(document.id) }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = stringResource(R.string.document_item_title_fmt, document.number, document.clientName ?: ""), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                Text(text = stringResource(R.string.planned, document.totalPlanned), style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Text(text = stringResource(R.string.actual, document.totalActual), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
