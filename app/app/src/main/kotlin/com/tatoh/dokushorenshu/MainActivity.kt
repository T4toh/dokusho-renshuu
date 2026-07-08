package com.tatoh.dokushorenshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.tatoh.dokushorenshu.ui.tema.TemaDokusho

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TemaDokusho { Text("読書練習") }
        }
    }
}
