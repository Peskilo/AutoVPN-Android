package dev.typenil.vpnclient.ui.common

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The product name lives in one place (brand_name); app_name aliases it, no locale overrides it. */
class BrandResourceTest {
    private fun strings(dir: String): Map<String, String> {
        val base = listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(base, "$dir/strings.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate {
            nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
        }
    }

    @Test fun `brand names keep their visible values`() {
        val en = strings("values")
        assertEquals("SayanVPN", en["brand_name"])
        assertEquals("SAYANVPN", en["brand_wordmark"])
        assertEquals("@string/brand_name", en["app_name"])
    }

    @Test fun `no locale overrides the brand`() {
        val ru = strings("values-ru")
        for (k in listOf("app_name", "brand_name", "brand_wordmark")) assertFalse(k, k in ru)
    }
}
