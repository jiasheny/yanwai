package dev.jev.wechatmood.reply

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ReplyKnowledgeCatalogTest {
    private val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }

    @Test fun `every advisor and role combination exists in the bundled original knowledge`() {
        ReplyAdvisor.entries.forEach { advisor ->
            ReplyRelationship.entries.forEach { role ->
                val paths = ReplyKnowledgeCatalog.paths(advisor, role)
                assertTrue("$advisor/$role has ${paths.size} paths", paths.size in 3..5)
                assertEquals(paths.distinct(), paths)
                paths.forEach { path ->
                    assertTrue("Missing $path for $advisor/$role", File(assets, path).readText().isNotBlank())
                }
            }
        }
    }

    @Test fun `injected knowledge stays within the request budget`() {
        ReplyAdvisor.entries.forEach { advisor ->
            ReplyRelationship.entries.forEach { role ->
                val bytes = ReplyKnowledgeCatalog.paths(advisor, role).sumOf { File(assets, it).length() }
                assertTrue("$advisor/$role loads $bytes bytes", bytes <= BUDGET_BYTES)
            }
        }
    }

    @Test fun `family and friend roles receive fitting guidance without dating references`() {
        listOf(ReplyRelationship.ELDER, ReplyRelationship.YOUNGER_SIBLING, ReplyRelationship.FAMILY).forEach { role ->
            val paths = ReplyKnowledgeCatalog.paths(ReplyAdvisor.JUNSHI, role)
            assertTrue(paths.any { it.contains("家庭") })
            assertFalse(paths.any { it.contains("吸引约会") })
            assertTrue(role.guidance.contains("不"))
        }
        assertTrue(ReplyKnowledgeCatalog.paths(ReplyAdvisor.JUNSHI, ReplyRelationship.CRUSH).any { it.contains("吸引约会") })
        assertTrue(ReplyKnowledgeCatalog.paths(ReplyAdvisor.JUNSHI, ReplyRelationship.FRIEND).any { it.contains("接话") })
    }

    @Test fun `advisor ids are unique and unknown ids fall back to the default`() {
        assertEquals(ReplyAdvisor.entries.map { it.id }.distinct().size, ReplyAdvisor.entries.size)
        assertEquals(ReplyAdvisor.DEFAULT, ReplyAdvisor.of(null))
        assertEquals(ReplyAdvisor.DEFAULT, ReplyAdvisor.of("nope"))
        assertEquals(ReplyAdvisor.QINGSHENG, ReplyAdvisor.of("qingsheng"))
        ReplyAdvisor.entries.forEach {
            assertTrue(it.sourceUrl.startsWith("https://github.com/"))
            assertTrue(it.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(it.label.isNotBlank() && it.note.isNotBlank() && it.shortLabel.isNotBlank())
            assertTrue(it.shortLabel.length <= 4)
            assertTrue("Missing license for ${it.label}", File(assets, it.licenseAsset).readText().contains("MIT License"))
        }
    }

    private companion object { const val BUDGET_BYTES = 56 * 1024 }
}
