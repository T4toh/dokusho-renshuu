package com.tatoh.dokushorenshu.ui.acerca

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val TEXTO_ACERCA = """Dokusho Renshū 0.1.0

Datos de diccionario:
• Jitendex (jitendex.org) — CC BY-SA 4.0
• KANJIDIC2 (EDRDG) — CC BY-SA 4.0
• Tatoeba (tatoeba.org) — CC-BY 2.0 FR

Historias: Aozora Bunko (aozora.gr.jp) — dominio público.
Cuentos de 楠山正雄.

Tokenización: Kuromoji (Atilika) — Apache License 2.0."""

@Composable
fun AcercaScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(TEXTO_ACERCA)
    }
}
