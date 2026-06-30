package com.theveloper.pixelplay.data.navidrome

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [selectAutoDownloadsToEvict] — the LRU eviction math that keeps the auto-download
 * pool within the user's budget. Manual downloads never reach this function (the DAO query that
 * feeds it already filters to AUTO rows), so these tests focus on ordering and the budget boundary.
 */
class NavidromeAutoDownloadEvictionTest {

    private val mb = 1024L * 1024L

    @Test
    fun `unlimited budget evicts nothing`() {
        val candidates = listOf("a" to 100L * mb, "b" to 100L * mb)
        val result = selectAutoDownloadsToEvict(candidates, currentTotalBytes = 200L * mb, budgetBytes = 0)
        assertThat(result).isEmpty()
    }

    @Test
    fun `under budget evicts nothing`() {
        val candidates = listOf("a" to 100L * mb)
        val result = selectAutoDownloadsToEvict(candidates, currentTotalBytes = 100L * mb, budgetBytes = 500L * mb)
        assertThat(result).isEmpty()
    }

    @Test
    fun `exactly at budget evicts nothing`() {
        val candidates = listOf("a" to 500L * mb)
        val result = selectAutoDownloadsToEvict(candidates, currentTotalBytes = 500L * mb, budgetBytes = 500L * mb)
        assertThat(result).isEmpty()
    }

    @Test
    fun `evicts oldest first until within budget`() {
        // Ordered least-recently-played first: a (oldest) .. d (newest). Total 400, budget 250.
        val candidates = listOf(
            "a" to 100L * mb,
            "b" to 100L * mb,
            "c" to 100L * mb,
            "d" to 100L * mb,
        )
        val result = selectAutoDownloadsToEvict(candidates, currentTotalBytes = 400L * mb, budgetBytes = 250L * mb)
        // Evict a (300), still > 250, evict b (200) <= 250 → stop. Newest c, d are kept.
        assertThat(result).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `stops as soon as one large eviction frees enough`() {
        val candidates = listOf(
            "big" to 300L * mb,
            "small" to 10L * mb,
        )
        val result = selectAutoDownloadsToEvict(candidates, currentTotalBytes = 310L * mb, budgetBytes = 100L * mb)
        assertThat(result).containsExactly("big")
    }

    @Test
    fun `evicts everything when budget smaller than the smallest remaining`() {
        val candidates = listOf("a" to 100L * mb, "b" to 100L * mb)
        val result = selectAutoDownloadsToEvict(candidates, currentTotalBytes = 200L * mb, budgetBytes = 1L)
        assertThat(result).containsExactly("a", "b").inOrder()
    }
}
