package cn.ppps.forwarder.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

//UMeng analytics (upstream's app key) must not ship: no dependency and no code referencing it
class NoAnalyticsTest {

    private fun moduleFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() }

    @Test
    fun buildGradle_hasNoUmengDependency() {
        val gradle = moduleFile("build.gradle").readText()
        assertTrue("umeng dependency still in build.gradle", !gradle.contains("com.umeng", ignoreCase = true))
    }

    @Test
    fun sources_doNotReferenceUmeng() {
        val offenders = moduleFile("src/main").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml") }
            .filter { it.readText().contains("com.umeng") }
            .map { it.path }
            .toList()
        assertTrue("files still referencing com.umeng: $offenders", offenders.isEmpty())
    }
}
