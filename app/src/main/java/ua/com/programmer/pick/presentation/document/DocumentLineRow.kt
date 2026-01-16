package ua.com.programmer.pick.presentation.document

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.domain.model.DocumentLine
import ua.com.programmer.pick.R

@Composable
fun DocumentLineRow(line: DocumentLine, onQuantityChange: (String, Double) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.padding(8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = line.productName)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.planned, line.plannedQuantity))
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Text(text = stringResource(R.string.actual, line.actualQuantity))
            }

            Spacer(modifier = Modifier.height(8.dp))

            QuantityStepper(value = line.actualQuantity) { newVal -> onQuantityChange(line.id, newVal) }
        }
    }
}
