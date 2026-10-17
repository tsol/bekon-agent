package pro.potoki.bekon.agent.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/** Every raw link in the audit prompt must point at a file that exists in the published repo layout. */
class SourceCodeTest {
    private val roots = generateSequence(File("").absoluteFile) { it.parentFile }.toList()

    /** Standalone repo: path as is. Monorepo: bekon-agent/ lives under apps/; root docs exist only in the standalone repo. */
    private fun locate(path: String): File? = roots.asSequence().flatMap { r ->
        sequenceOf(File(r, path), File(r, "apps/$path"))
    }.firstOrNull { it.isFile }

    private val standalone = roots.any { File(it, "SECURITY-AUDIT.md").isFile && File(it, "bekon-agent").isDirectory }

    @Test fun keyFilesExist() {
        for (p in SourceCode.KEY_FILES) {
            if (!p.startsWith("bekon-agent/")) { if (!standalone) continue }
            assertTrue("missing $p", locate(p) != null)
        }
    }

    @Test fun rootDocsExistInStandalone() {
        Assume.assumeTrue("monorepo checkout", standalone)
        for (p in listOf("SECURITY-AUDIT.md", "AUDIT.md", "llms.txt")) assertTrue("missing $p", locate(p) != null)
    }

    @Test fun urlsAreTagPinned() {
        assertEquals("https://github.com/tsol/bekon-agent/archive/refs/tags/v9.9.9.zip", SourceCode.zipUrl("9.9.9"))
        assertEquals("https://raw.githubusercontent.com/tsol/bekon-agent/v9.9.9/SECURITY-AUDIT.md", SourceCode.rawUrl("SECURITY-AUDIT.md", "9.9.9"))
        assertEquals("https://github.com/tsol/bekon-agent/tree/v9.9.9", SourceCode.treeUrl("9.9.9"))
    }
}
