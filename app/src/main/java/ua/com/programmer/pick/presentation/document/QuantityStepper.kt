package ua.com.programmer.pick.presentation.document

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ua.com.programmer.pick.R

@Composable
fun QuantityStepper(value: Double, onChange: (Double) -> Unit) {
    Button(onClick = { onChange((value - 1).coerceAtLeast(0.0)) }) {
        Text(text = stringResource(R.string.minus))
    }
    Text(text = "$value")
    Button(onClick = { onChange(value + 1) }) {
        Text(text = stringResource(R.string.plus))
    }
}
