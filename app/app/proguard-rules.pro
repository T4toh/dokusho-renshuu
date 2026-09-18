# Reglas de ProGuard/R8 específicas de este proyecto.
# Las reglas por defecto de Android (getDefaultProguardFile) ya cubren los casos
# estándar (Activity/View/etc, anotaciones, atributos necesarios para debug).
#
# Room: el código de acceso a datos usa las clases generadas por KSP directamente
# (sin reflexión en runtime para instanciarlas), así que no necesita keep rules
# propias más allá de las que ya aporta la librería vía su propio consumer-rules.
#
# kotlinx-serialization: el proyecto parsea JSON manualmente con JsonElement/
# JsonObject (sin @Serializable ni serializers generados), así que no depende de
# reflexión de kotlinx.serialization que R8 pudiera romper.
#
# Kuromoji (IPADIC): carga su diccionario binario desde classpath resources y
# usa reflexión interna para resolver el formato del diccionario. Confirmado en
# dispositivo (release sin estas reglas): "RuntimeException: Could not load
# dictionaries" al arrancar — R8 renombra/poda clases que Kuromoji resuelve por
# nombre en tiempo de ejecución. Necesarias.
-keep class com.atilika.kuromoji.** { *; }
-dontwarn com.atilika.kuromoji.**

# ML Kit (reconocimiento de texto japonés): mismo cuadro que Kuromoji, y encontrado
# igual — sólo falla en release. Con R8 sin estas reglas, construir el TextRecognizer
# tira "NullPointerException: Attempt to read from field 'g04 iz3.a' on a null object
# reference in method 'void od1.<init>()'", donde iz3 es
# com.google.mlkit.vision.text.internal.zzo y od1 es nuestro OcrJapones: R8 poda parte
# de la inicialización estática que ML Kit resuelve por su cuenta. El síntoma en la app
# NO es un crash —OcrJapones deja propagar y el Service sustituye por texto vacío— así
# que se ve como "OCR devolvió 0 chars" en cada captura: la feature entera muda.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**
