package com.tatoh.dokushorenshu.ui.acerca

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AcercaScreen() {
    Surface(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),  // edge-to-edge de Task 1
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("Dokusho Renshū 0.1.0", style = MaterialTheme.typography.headlineSmall)

            Text("Dictionary data", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 20.dp))
            Text("• Jitendex (jitendex.org) — CC BY-SA 4.0")
            Text("• KANJIDIC2 (EDRDG) — CC BY-SA 4.0")
            Text("• Tatoeba (tatoeba.org) — CC-BY 2.0 FR")

            Text("Stories", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 20.dp))
            Text("• Aozora Bunko (aozora.gr.jp) — public domain")
            Text("• Tales by 楠山正雄")

            Text("Tokenization", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 20.dp))
            Text("• Kuromoji (Atilika) — Apache License 2.0")
        }
    }
}
