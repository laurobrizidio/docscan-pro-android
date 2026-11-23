package br.com.docscan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import br.com.docscan.data.DocumentDatabase
import br.com.docscan.data.LocalDocumentRepository
import br.com.docscan.data.LocalScanRepository
import br.com.docscan.data.LocalSettingsRepository
import br.com.docscan.data.MlKitTextRecognizer
import br.com.docscan.data.PdfFiles
import br.com.docscan.data.imaging.PageRenderer
import br.com.docscan.ui.HomeViewModel
import br.com.docscan.ui.ScanRoute
import br.com.docscan.ui.ScanViewModel
import br.com.docscan.ui.SettingsViewModel

class MainActivity : ComponentActivity() {
    private val settings by lazy { LocalSettingsRepository(applicationContext) }
    private val pdfFiles by lazy { PdfFiles(applicationContext) }
    private val scans by lazy { LocalScanRepository(applicationContext, pdfFiles) }
    private val recognizer by lazy { MlKitTextRecognizer(PageRenderer()) { scans.imageFile(it.id) } }
    private val documents by lazy {
        val database = Room.databaseBuilder(applicationContext, DocumentDatabase::class.java, "documents.db").build()
        LocalDocumentRepository(applicationContext, database.documents(), scans, pdfFiles, settings)
    }
    private val scanner by viewModels<ScanViewModel> {
        viewModelFactory { initializer { ScanViewModel(scans, documents, recognizer, settings) } }
    }
    private val library by viewModels<HomeViewModel> {
        viewModelFactory { initializer { HomeViewModel(documents, documents::thumbnail) } }
    }
    private val preferences by viewModels<SettingsViewModel> {
        viewModelFactory { initializer { SettingsViewModel(settings) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val systemBars = WindowCompat.getInsetsController(window, window.decorView)
        setContent {
            ScanRoute(this, scanner, library, preferences) { dark ->
                systemBars.isAppearanceLightStatusBars = !dark
                systemBars.isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
