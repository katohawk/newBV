package com.kuaishou.akdanmaku.ext

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 验证时间切片二分边界不会包含窗外项，并正确处理重复时间戳。 */
class CollectionsTest {
    @Test
    fun `slice bounds exclude values outside a gap`() {
        val values = listOf(1_000L, 400_000L, 800_000L)
        assertThat(values.binarySearchAtLeast(-3_800L) { it }).isEqualTo(0)
        assertThat(values.binarySearchAtMost(3_800L) { it }).isEqualTo(0)
        assertThat(values.binarySearchAtLeast(1_001L) { it }).isEqualTo(1)
        assertThat(values.binarySearchAtMost(399_999L) { it }).isEqualTo(0)
    }

    @Test
    fun `equal timestamps include first through last matching item`() {
        val values = listOf(1L, 2L, 2L, 2L, 3L)
        assertThat(values.binarySearchAtLeast(2L) { it }).isEqualTo(1)
        assertThat(values.binarySearchAtMost(2L) { it }).isEqualTo(3)
    }

    @Test
    fun `empty or out of range bounds return absent index`() {
        assertThat(emptyList<Long>().binarySearchAtLeast(0L) { it }).isEqualTo(-1)
        assertThat(emptyList<Long>().binarySearchAtMost(0L) { it }).isEqualTo(-1)
        assertThat(listOf(1L).binarySearchAtLeast(2L) { it }).isEqualTo(-1)
        assertThat(listOf(1L).binarySearchAtMost(0L) { it }).isEqualTo(-1)
        assertThat(listOf(1L).binarySearchAtLeast(1L) { it }).isEqualTo(0)
        assertThat(listOf(1L).binarySearchAtMost(1L) { it }).isEqualTo(0)
    }
}
