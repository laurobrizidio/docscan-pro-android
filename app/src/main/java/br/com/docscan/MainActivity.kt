package br.com.docscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import br.com.docscan.data.LocalScanRepository
import br.com.docscan.ui.ScanRoute
import br.com.docscan.ui.ScanViewModel

class MainActivity : ComponentActivity() {
    private val scanner by viewModels<ScanViewModel> {
        viewModelFactory { initializer { ScanViewModel(LocalScanRepository(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val systemBars = WindowCompat.getInsetsController(window, window.decorView)
        setContent {
            ScanRoute(this, scanner) { dark ->
                systemBars.isAppearanceLightStatusBars = !dark
                systemBars.isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
