package ua.com.programmer.pick.presentation.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ua.com.programmer.pick.presentation.common.LoadingLogo
import ua.com.programmer.pick.R

@Composable
fun SplashScreen(
    uiState: SplashUiState,
    onStart: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) {
        onStart()
    }

    val state by rememberUpdatedState(uiState)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LoadingLogo()

                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
                }

                state.errorMessage?.let { errorKey ->
                    val errorText = if (errorKey == SplashViewModel.ERROR_INITIALIZATION) stringResource(R.string.initialization_error) else errorKey
                    Text(text = errorText, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                    Button(onClick = onStart, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.retry))
                    }
                }

                state.targetRoute?.let { target ->
                    LaunchedEffect(target) {
                        onNavigate(target)
                    }
                }
            }
        }
    }
}
