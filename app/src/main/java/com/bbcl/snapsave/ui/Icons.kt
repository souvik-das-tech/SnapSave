package com.bbcl.snapsave.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material symbols that are missing from material-icons-core (avoids the huge extended set). */
object AppIcons {
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Download = icon("Download", "M5,20h14v-2H5v2zM19,9h-4V3H9v6H5l7,7 7,-7z")
    val Paste = icon(
        "Paste",
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0S9.6,0.84 9.18,2H5C3.9,2 3,2.9 3,4v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4C21,2.9 20.1,2 19,2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2V20z",
    )
    val Link = icon(
        "Link",
        "M3.9,12c0,-1.71 1.39,-3.1 3.1,-3.1h4V7H7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5h4v-1.9H7C5.29,15.1 3.9,13.71 3.9,12zM8,13h8v-2H8V13zM17,7h-4v1.9h4c1.71,0 3.1,1.39 3.1,3.1s-1.39,3.1 -3.1,3.1h-4V17h4c2.76,0 5,-2.24 5,-5S19.76,7 17,7z",
    )
    val Music = icon(
        "Music",
        "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z",
    )
    val Video = icon(
        "Video",
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4C3.45,6 3,6.45 3,7v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11L17,10.5z",
    )
    val Bolt = icon("Bolt", "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z")

    private fun icon(name: String, path: String) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
}
