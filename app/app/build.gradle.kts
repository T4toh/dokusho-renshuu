import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.tatoh.dokushorenshu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tatoh.dokushorenshu"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true  // Robolectric
        }
    }
    packaging {
        // kuromoji-ipadic y kuromoji-core traen metadata duplicada (licencias/autores).
        resources {
            excludes += "/META-INF/{AUTHORS,CONTRIBUTORS,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt,DEPENDENCIES}*"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kuromoji.ipadic)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---- assets generados: db del release + historias del catálogo del monorepo ----

val dirAssets = layout.projectDirectory.dir("src/main/assets")

val descargarDiccionario = tasks.register("descargarDiccionario") {
    val destino = dirAssets.file("diccionario-v1.db").asFile
    outputs.file(destino)
    onlyIf { !destino.exists() }
    doLast {
        destino.parentFile.mkdirs()
        // se descarga a un archivo temporal para que una descarga interrumpida nunca deje
        // un archivo parcial en el destino final (onlyIf lo detectaría como "ya existe" y
        // el require de tamaño nunca volvería a correr).
        val temporal = destino.resolveSibling("${destino.name}.tmp")
        if (temporal.exists()) temporal.delete() // descarta restos de una corrida anterior fallida
        val url = "https://github.com/T4toh/dokusho-renshuu/releases/download/db-v1/diccionario-v1.db"
        println("Descargando diccionario-v1.db (~79 MB) de $url ...")
        try {
            URI(url).toURL().openStream().use { entrada ->
                temporal.outputStream().use { salida -> entrada.copyTo(salida) }
            }
            require(temporal.length() > 70_000_000) {
                "db descargado sospechosamente chico (${temporal.length()} bytes): descartado, reintentar"
            }
            // recién acá, con el archivo ya validado, se promueve al destino final
            check(temporal.renameTo(destino)) { "no se pudo mover $temporal a $destino" }
        } finally {
            if (temporal.exists()) temporal.delete() // limpia si algo falló antes del rename
        }
    }
}

val copiarHistorias = tasks.register<Copy>("copiarHistorias") {
    // fuente de verdad: catalogo/ en la raíz del monorepo (un nivel arriba del proyecto gradle)
    from(rootProject.layout.projectDirectory.dir("../catalogo/historias")) { into("historias") }
    from(rootProject.layout.projectDirectory.file("../catalogo/catalogo.json"))
    into(dirAssets)
}

tasks.named("preBuild") {
    dependsOn(descargarDiccionario, copiarHistorias)
}
