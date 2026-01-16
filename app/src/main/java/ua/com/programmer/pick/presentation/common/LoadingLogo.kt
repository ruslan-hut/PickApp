package ua.com.programmer.pick.presentation.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import ua.com.programmer.pick.R

@Composable
fun LoadingLogo(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.title_app),
        style = MaterialTheme.typography.headlineLarge,
        fontSize = 28.sp,
        modifier = modifier
    )
}
