package com.eagalts.still

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Каждая строка интерфейса `t("…")` переведена во все 7 языков и плейсхолдеры {0} не потерялись. */
@RunWith(RobolectricTestRunner::class)
class I18nTest {
    private val src = File("src/main/java")
    private val dicts = File("src/main/assets/i18n")

    private fun strings(): Set<String> {
        val re = Regex("""\bt\("((?:[^"\\]|\\.)*)"""")
        return src.walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1].replace("\\\"", "\"") } }
            .toSortedSet()
    }

    @Test fun everyStringTranslated() {
        val all = strings()
        assertTrue("строк не нашлось", all.size > 20)
        val problems = ArrayList<String>()
        for (l in I18n.languages) {
            val o = JSONObject(File(dicts, "$l.json").readText())
            for (s in all) {
                if (!o.has(s)) { problems += "$l: нет «$s»"; continue }
                val tr = o.getString(s)
                Regex("\\{\\d}").findAll(s).forEach { m -> if (m.value !in tr) problems += "$l: «$s» потерял ${m.value}" }
            }
            o.keys().forEach { k -> if (k !in all) problems += "$l: лишняя «$k»" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
