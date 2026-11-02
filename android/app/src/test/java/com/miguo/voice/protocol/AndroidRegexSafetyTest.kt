package com.miguo.voice.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * fix1 回归测试：真机崩溃根因是 Regex("(?U)...")——桌面 JDK 支持 (?U)，Android 的 ICU 正则不支持，
 * 类初始化抛 PatternSyntaxException → ExceptionInInitializerError。JVM 单测用的是 JDK 正则，测不出来，
 * 所以这里做“静态扫描”：main 源码里不允许出现 Android 不支持的正则语法，并逐个编译剩余的正则。
 */
class AndroidRegexSafetyTest {
    private val srcRoot = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    /** Android(ICU) 不支持或与 JDK 行为不同的写法 */
    private val forbidden = listOf(
        "(?U)", "UNICODE_CHARACTER_CLASS", "(?u", "\\p{Is", "\\p{java", "\\p{IsAlphabetic", "(?<", "\\R", "\\X",
        "Pattern.CANON_EQ", "Pattern.UNICODE_CASE",
    )
    private val regexCall = Regex("""(?:Regex|Pattern\.compile|toRegex|toPattern)\s*\(\s*(?:"((?:[^"\\]|\\.)*)")?""")

    private fun sources() = srcRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun noAndroidUnsupportedRegexSyntax() {
        val hits = mutableListOf<String>()
        for (f in sources()) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (!regexCall.containsMatchIn(code)) return@forEachIndexed
                for (bad in forbidden) if (code.contains(bad)) hits += "${f.name}:${i + 1}: $bad → $line"
            }
        }
        assertTrue("Android 不支持的正则写法：\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun everyLiteralPatternCompiles() {
        var n = 0
        for (f in sources()) for (m in regexCall.findAll(f.readText())) {
            val lit = m.groupValues[1].takeIf { it.isNotEmpty() } ?: continue
            val pattern = lit.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")
            Regex(pattern)     // 不抛异常即通过
            n++
        }
        assertTrue("至少应扫描到 1 个正则（扫描器自身是否正常）", n >= 1)
    }

    @Test fun sessionIdAndUserIdMatchServerSemantics() {
        // 服务端 Python: ^[\w\-.:]{1,64}$ / ^[\w\-.@]{1,64}$（str 模式，\w 含 Unicode 字母数字）
        assertTrue(SessionIdFactory.isValid(SessionIdFactory.make()))
        assertTrue(SessionIdFactory.isValid("android-20261026-093600-ab12"))
        assertTrue(SessionIdFactory.isValid("a:b.c-d_e"))
        assertFalse(SessionIdFactory.isValid("a@b"))
        assertFalse(SessionIdFactory.isValid("a b"))
        assertFalse(SessionIdFactory.isValid(""))
        assertFalse(SessionIdFactory.isValid("x".repeat(65)))
        assertTrue(UserIdValidator.isValid("jason"))
        assertTrue(UserIdValidator.isValid("a.b-c_d@e"))
        assertTrue(UserIdValidator.isValid("米果"))
        assertTrue(UserIdValidator.isValid("x".repeat(64)))
        assertFalse(UserIdValidator.isValid("a:b"))
        assertFalse(UserIdValidator.isValid("a/b"))
        assertFalse(UserIdValidator.isValid("a＠b"))
        assertFalse(UserIdValidator.isValid("😀"))
    }

    @Test fun headerValues() {
        assertTrue(HeaderValues.isSafe("jason"))
        assertFalse(HeaderValues.isSafe("米果"))
        assertFalse(HeaderValues.isSafe("a b"))
        assertFalse(HeaderValues.isSafe(""))
        assertEquals('米', HeaderValues.firstBadChar("a米"))
        assertEquals("abc123", HeaderValues.cleanApiKey("  ａｂｃ１２３\u200B\n"))
        assertEquals(null, HeaderValues.cleanApiKey("   "))
        assertEquals("密钥", HeaderValues.cleanApiKey(" 密钥 "))
    }
}
