package com.tatoh.dokushorenshu.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseInfoTest {
    private fun release(
        tag: String,
        draft: Boolean = false,
        assets: String = "",
    ) = """{"tag_name":"$tag","draft":$draft,"prerelease":true,"assets":[$assets]}"""

    private fun apk(tag: String, digest: String? = "sha256:ABCDEF0123", url: String = "https://github.com/T4toh/dokusho-renshuu/releases/download/$tag/dokusho-renshuu-$tag.apk") =
        "{\"name\":\"dokusho-renshuu-$tag.apk\",\"browser_download_url\":\"$url\"" +
            (if (digest != null) ",\"digest\":\"$digest\"" else "") + "}"

    @Test
    fun `elige la mayor version con apk y digest`() {
        val json = "[" + listOf(
            release("v0.1.0-beta.4", assets = apk("v0.1.0-beta.4")),
            release("v0.1.0-beta.5", assets = apk("v0.1.0-beta.5", digest = "sha256:DEADBEEF")),
            release("v0.1.0-beta.3", assets = apk("v0.1.0-beta.3")),
        ).joinToString(",") + "]"
        val info = ReleaseInfo.desdeReleases(json)!!
        assertEquals(Version(0, 1, 0, listOf("beta", "5")), info.version)
        assertEquals("https://github.com/T4toh/dokusho-renshuu/releases/download/v0.1.0-beta.5/dokusho-renshuu-v0.1.0-beta.5.apk", info.apkUrl)
        assertEquals("deadbeef", info.sha256) // sin prefijo, en minúsculas
    }

    @Test
    fun `ignora db-vN, drafts, sin apk, sin digest y http plano`() {
        val json = "[" + listOf(
            release("db-v2", assets = """{"name":"diccionario-v2.db","browser_download_url":"https://x/y.db","digest":"sha256:00"}"""),
            release("v9.0.0", draft = true, assets = apk("v9.0.0")),
            release("v8.0.0"),                                   // sin assets
            release("v7.0.0", assets = apk("v7.0.0", digest = null)),
            release("v6.0.0", assets = apk("v6.0.0", digest = "md5:00")),
            release("v5.0.0", assets = apk("v5.0.0", url = "http://github.com/x.apk")),
            release("v0.1.0-beta.4", assets = apk("v0.1.0-beta.4")),
        ).joinToString(",") + "]"
        assertEquals(Version(0, 1, 0, listOf("beta", "4")), ReleaseInfo.desdeReleases(json)!!.version)
    }

    @Test
    fun `sin candidatas devuelve null`() {
        assertNull(ReleaseInfo.desdeReleases("[]"))
        assertNull(ReleaseInfo.desdeReleases("[" + release("db-v2") + "]"))
    }

    @Test
    fun `campos desconocidos no molestan`() {
        val json = """[{"tag_name":"v1.0.0","draft":false,"html_url":"x","author":{"login":"t"},"assets":[${apk("v1.0.0")}]}]"""
        assertEquals(Version(1, 0, 0), ReleaseInfo.desdeReleases(json)!!.version)
    }
}
