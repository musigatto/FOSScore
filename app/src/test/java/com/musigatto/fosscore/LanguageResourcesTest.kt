package com.musigatto.fosscore

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/**
 * Checks the translation folders for what would break the app: a translated string asking for a
 * value (%1$d...) the code does not pass crashes when it is shown. Runs on the XML files themselves
 * (unit tests run from the module directory), so such a translation fails here rather than on a
 * device.
 *
 * A missing translation is not checked: Android shows the English text instead, and translations
 * follow the code at their own pace (contributors, Crowdin). A key left in a translation after it
 * was removed from English is not checked either: it is never shown.
 */
class LanguageResourcesTest {

    private val res = File("src/main/res")
    private val placeholder = Regex("""%(\d+\$)?[sd]""")

    private fun strings(folder: String): Map<String, Element> {
        val file = File(res, "$folder/strings.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associateBy { it.getAttribute("name") }
    }

    // values-es, values-fr...
    private val translationFolders: List<String>
        get() = res.listFiles()!!
            .filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
            .map { it.name }

    @Test
    fun translationsKeepTheSamePlaceholders() {
        val default = strings("values")
        translationFolders.forEach { folder ->
            strings(folder).forEach { (key, element) ->
                val source = default[key] ?: return@forEach
                val expected = placeholder.findAll(source.textContent).map { it.value }.sorted().toList()
                val actual = placeholder.findAll(element.textContent).map { it.value }.sorted().toList()
                assertEquals("$folder: placeholders of '$key'", expected, actual)
            }
        }
    }
}
