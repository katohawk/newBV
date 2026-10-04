package dev.frost819.newbv.app.ui.screen.main

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.datastore.LeftNaviItem
import org.junit.jupiter.api.Test

/** 验证首页左侧导航顺序。 */
class LeftNaviDisplayOrderTest {
    @Test
    fun `home appears before personal with other navigation items unchanged`() {
        // Given / When
        val items = LeftNaviItem.entries

        // Then
        assertThat(items)
            .containsExactly(
                LeftNaviItem.Search,
                LeftNaviItem.Home,
                LeftNaviItem.Personal,
                LeftNaviItem.UGC,
                LeftNaviItem.PGC,
                LeftNaviItem.Live,
            ).inOrder()
    }
}
