package com.musigatto.fosscore.ui.library

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetDetailInsetsTest {

    @Test
    fun theDetailsScreenKeepsClearOfTheStatusBar() {
        // The app draws edge to edge, and the details screen is shown over the whole window (not
        // in the library's Scaffold): its title "Edit the score" sat under the status bar, against
        // the top edge. Padded past the system bars as the preferences screen is.
        val screen = File("src/main/java/com/musigatto/fosscore/ui/library/SheetDetailScreen.kt").readText()

        assertTrue(".windowInsetsPadding(WindowInsets.safeDrawing)" in screen)
    }
}
