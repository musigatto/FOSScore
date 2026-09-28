package com.musigatto.fosscore

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musigatto.fosscore.ui.theme.FOSScoreTheme
import com.musigatto.fosscore.ui.viewer.PdfViewerScreen
import com.musigatto.fosscore.ui.viewer.Settings
import com.musigatto.fosscore.ui.viewer.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(Settings.themeMode(context)) }
            val toggleTheme = {
                themeMode = themeMode.next()
                Settings.setThemeMode(context, themeMode)
            }
            FOSScoreTheme(darkTheme = themeMode.darkTheme(isSystemInDarkTheme())) {
                var pdfUri by rememberSaveable { mutableStateOf<Uri?>(null) }
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { pdfUri = it }

                val uri = pdfUri
                if (uri != null) {
                    PdfViewerScreen(
                        pdfUri = uri,
                        onBack = { pdfUri = null },
                        themeMode = themeMode,
                        onToggleTheme = toggleTheme
                    )
                } else {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Button(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                                Text("Open PDF")
                            }
                            Spacer(Modifier.width(16.dp))
                            Text("Select a PDF to view a music score")
                        }
                    }
                }
            }
        }
    }
}