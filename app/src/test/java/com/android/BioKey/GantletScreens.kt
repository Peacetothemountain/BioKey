package com.android.BioKey

/**
 * The screens the gantlet tests run BioKey's lock screen on, as Robolectric qualifiers (sizes in
 * dp at each device's density). Foldables have two: folding or unfolding one moves whatever is on
 * screen from one to the other, as a configuration change.
 *
 * [freeform]: whether the device reports freeform windows (FEATURE_FREEFORM_WINDOW_MANAGEMENT).
 * Recent Pixels and every Samsung phone do (Samsung for pop-up view); Samsung's open an activity
 * started with launch bounds in a pop-up window, even on the phone's own screen.
 */
internal data class GantletScreen(val name: String, val qualifiers: String, val freeform: Boolean)

internal object GantletScreens {
    val PHONE = GantletScreen("Phone", "w411dp-h914dp-port-420dpi", freeform = false)
    val PIXEL_11_PRO_XL = GantletScreen("Pixel 11 Pro XL", "w573dp-h1276dp-port-375dpi", freeform = true)
    val FOLD7_COVER = GantletScreen("Galaxy Z Fold7 cover screen", "w411dp-h960dp-port-420dpi", freeform = true)
    val FOLD7_INNER = GantletScreen("Galaxy Z Fold7 main screen", "w842dp-h934dp-port-374dpi", freeform = true)
    val FOLD7_INNER_LANDSCAPE = GantletScreen("Galaxy Z Fold7 main screen, landscape", "w934dp-h842dp-land-374dpi", freeform = true)
    val FLIP7_COVER = GantletScreen("Galaxy Z Flip7 cover screen", "w361dp-h399dp-port-420dpi", freeform = true)
    val FLIP7_MAIN = GantletScreen("Galaxy Z Flip7 main screen", "w411dp-h960dp-port-420dpi", freeform = true)
    val PIXEL_FOLD_COVER = GantletScreen("Pixel 10 Pro Fold cover screen", "w411dp-h923dp-port-420dpi", freeform = true)
    val PIXEL_FOLD_INNER = GantletScreen("Pixel 10 Pro Fold main screen", "w891dp-h923dp-port-373dpi", freeform = true)
    val TABLET = GantletScreen("Tablet", "w1280dp-h800dp-land-xhdpi", freeform = true)

    val ALL = listOf(
        PHONE, PIXEL_11_PRO_XL, FOLD7_COVER, FOLD7_INNER, FOLD7_INNER_LANDSCAPE, FLIP7_COVER, FLIP7_MAIN,
        PIXEL_FOLD_COVER, PIXEL_FOLD_INNER, TABLET
    )

    /** What folding and unfolding a foldable, and turning it, does to the screen BioKey is on. */
    val FOLD_SEQUENCES = listOf(
        listOf(FOLD7_COVER, FOLD7_INNER, FOLD7_INNER_LANDSCAPE, FOLD7_INNER, FOLD7_COVER),
        listOf(PIXEL_FOLD_COVER, PIXEL_FOLD_INNER, PIXEL_FOLD_COVER),
        listOf(FLIP7_COVER, FLIP7_MAIN, FLIP7_COVER)
    )
}
