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
