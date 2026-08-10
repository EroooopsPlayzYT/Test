package com.eroooops.wifispeedtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eroooops.wifispeedtest.ui.SpeedTestScreen
import com.eroooops.wifispeedtest.ui.SpeedTestViewModel
import com.eroooops.wifispeedtest.ui.theme.WifiSpeedTestTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WifiSpeedTestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: SpeedTestViewModel = viewModel()
                    SpeedTestScreen(viewModel)
                }
            }
        }
    }
}
