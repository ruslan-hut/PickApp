package ua.com.programmer.pick.presentation.common

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ua.com.programmer.pick.R

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value = query, onValueChange = onQueryChange, label = { Text(stringResource(R.string.search_label)) }, modifier = modifier)
}
