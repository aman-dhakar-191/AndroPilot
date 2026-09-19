package com.andropilot.core.testing

import com.andropilot.core.driver.DriverOutcome
import com.andropilot.core.model.Bounds
import com.andropilot.core.model.ElementRole
import com.andropilot.core.model.Insets
import com.andropilot.core.model.ScreenMetrics
import com.andropilot.core.model.UiElement

/**
 * A catalogue of representative Android screens.
 *
 * Each one encodes a structure that breaks naive automation, so the SDK's behaviour against
 * them is pinned by tests: duplicate labels, an unlabelled icon bar, a list that only
 * reveals its target after scrolling, a confirmation dialog, a screen with no accessibility
 * information at all.
 */
public object FakeScreens {

    public val PHONE: ScreenMetrics = ScreenMetrics(
        widthPx = 1080,
        heightPx = 1920,
        density = 3.0f,
        orientation = com.andropilot.core.model.Orientation.PORTRAIT,
        systemInsets = Insets(top = 72, bottom = 120),
    )

    /** A login form: two fields, a primary button, and a low-emphasis secondary action. */
    public fun login(): FakeScreen {
        val ids = listOf("title", "user", "pass", "submit", "forgot")
        val screen = FakeScreen(
            packageName = "com.example.shop",
            windowTitle = "Sign in",
            metrics = PHONE,
            elements = listOf(
                Ui.root("com.example.shop", PHONE, ids),
                Ui.label("title", "Welcome back", Bounds(60, 300, 1020, 400)),
                Ui.field("user", "Email address", Bounds(60, 500, 1020, 640), resourceId = "com.example.shop:id/email"),
                Ui.field("pass", "Password", Bounds(60, 700, 1020, 840), password = true, resourceId = "com.example.shop:id/password"),
                Ui.button("submit", "Sign in", Bounds(60, 920, 1020, 1060), resourceId = "com.example.shop:id/sign_in"),
                Ui.button("forgot", "Forgot password?", Bounds(60, 1100, 1020, 1180)),
            ),
        )
        screen.clickHandler = { s, element ->
            if (element.id == "submit") {
                val filled = s.elements.first { it.id == "user" }.text.isNullOrBlank().not()
                if (filled) {
                    val target = home()
                    s.windowTitle = target.windowTitle
                    s.replaceAll(target.elements)
                } else {
                    s.add(Ui.label("error", "Enter your email address", Bounds(60, 1250, 1020, 1320)))
                    s.update("root") { it.copy(childIds = it.childIds + "error") }
                }
            }
        }
        return screen
    }

    /** A home screen with a bottom navigation bar of unlabelled icons. */
    public fun home(): FakeScreen {
        val ids = listOf("greeting", "search", "nav_home", "nav_orders", "nav_profile")
        return FakeScreen(
            packageName = "com.example.shop",
            windowTitle = "Home",
            metrics = PHONE,
            elements = listOf(
                Ui.root("com.example.shop", PHONE, ids),
                Ui.label("greeting", "Hello, Sam", Bounds(60, 200, 1020, 290)),
                Ui.field("search", "Search products", Bounds(60, 340, 1020, 460)),
                Ui.unlabelledIcon("nav_home", Bounds(60, 1700, 400, 1800)),
                Ui.unlabelledIcon("nav_orders", Bounds(400, 1700, 700, 1800)),
                Ui.unlabelledIcon("nav_profile", Bounds(700, 1700, 1020, 1800)),
            ),
        )
    }

    /**
     * A settings list whose target row sits below the fold. `scrollStepPx` controls how far
     * each scroll travels; set it to 0 to simulate a list already at its end.
     */
    public fun settingsList(rows: Int = 14, targetLabel: String = "Delete account"): FakeScreen {
        val rowHeight = 160
        val listTop = 300
        val childIds = (0 until rows).map { "row$it" }
        val items = childIds.mapIndexed { i, id ->
            val label = if (i == rows - 1) targetLabel else "Setting option ${i + 1}"
            Ui.listItem(
                id = id,
                text = label,
                bounds = Bounds(0, listTop + i * rowHeight, 1080, listTop + (i + 1) * rowHeight),
                parentId = "settings_list",
            ).copy(visible = listTop + (i + 1) * rowHeight <= PHONE.heightPx)
        }
        return FakeScreen(
            packageName = "com.example.shop",
            windowTitle = "Settings",
            metrics = PHONE,
            elements = buildList {
                add(Ui.root("com.example.shop", PHONE, listOf("settings_list")))
                add(Ui.list("settings_list", Bounds(0, listTop, 1080, 1800), childIds))
                addAll(items)
            },
        )
    }

    /** Two buttons with the same label, differing only by position: the ambiguity case. */
    public fun duplicateButtons(): FakeScreen = FakeScreen(
        packageName = "com.example.shop",
        windowTitle = "Compare plans",
        metrics = PHONE,
        elements = listOf(
            Ui.root("com.example.shop", PHONE, listOf("plan_a", "buy_a", "plan_b", "buy_b")),
            Ui.label("plan_a", "Basic", Bounds(60, 300, 520, 380)),
            Ui.button("buy_a", "Choose", Bounds(60, 420, 520, 540)),
            Ui.label("plan_b", "Pro", Bounds(560, 300, 1020, 380)),
            Ui.button("buy_b", "Choose", Bounds(560, 420, 1020, 540)),
        ),
    )

    /** A destructive-confirmation dialog. */
    public fun confirmDialog(): FakeScreen = FakeScreen(
        packageName = "com.example.shop",
        windowTitle = "Delete account?",
        metrics = PHONE,
        hasDialog = true,
        elements = listOf(
            Ui.root("com.example.shop", PHONE, listOf("dialog")),
            UiElement(
                id = "dialog",
                role = ElementRole.DIALOG,
                bounds = Bounds(120, 700, 960, 1200),
                className = "android.app.Dialog",
                childIds = listOf("dialog_title", "cancel", "confirm"),
                parentId = "root",
                depth = 1,
            ),
            Ui.label("dialog_title", "This cannot be undone.", Bounds(160, 760, 920, 860), parentId = "dialog", depth = 2),
            Ui.button("cancel", "Cancel", Bounds(160, 1020, 520, 1140), parentId = "dialog", depth = 2),
            Ui.button("confirm", "Delete", Bounds(560, 1020, 920, 1140), parentId = "dialog", depth = 2),
        ),
    )

    /**
     * A screen exposing nothing but an opaque container -- a game, a canvas, or an app that
     * simply does not implement accessibility. Only visual fallback can work here.
     */
    public fun opaqueCanvas(): FakeScreen = FakeScreen(
        packageName = "com.example.game",
        windowTitle = null,
        metrics = PHONE,
        elements = listOf(
            UiElement(
                id = "root",
                role = ElementRole.CONTAINER,
                bounds = PHONE.frame,
                className = "android.view.SurfaceView",
                parentId = null,
                depth = 0,
            ),
        ),
    )

    /**
     * A system-settings-shaped screen: a deep chain of single-child layout wrappers, a few
     * zero-sized stubs with resource ids, and only a handful of rows that mean anything.
     *
     * Modelled on a real `com.android.settings` capture, where 123 detected elements
     * included a ten-deep spine of full-screen containers and several `[0,296][1220,296]`
     * stubs. It is the case that tells you whether the compact rendering is actually
     * compact.
     */
    public fun deeplyWrappedSettings(wrapperDepth: Int = 10, rows: Int = 4): FakeScreen {
        val elements = ArrayList<UiElement>()
        val frame = PHONE.frame

        // The wrapper spine: every node full-screen, single-child, no label, no actions.
        var previousId: String? = null
        var spineId = "root"
        for (i in 0 until wrapperDepth) {
            val id = if (i == 0) "root" else "w$i"
            elements += UiElement(
                id = id,
                role = ElementRole.CONTAINER,
                bounds = frame,
                className = "android.widget.FrameLayout",
                resourceId = "com.android.settings:id/wrapper_$i",
                parentId = previousId,
                childIds = listOf(if (i == wrapperDepth - 1) "settings_list" else "w${i + 1}"),
                depth = i,
            )
            previousId = id
            spineId = id
        }

        // Zero-sized stubs that carry a resource id. Real hierarchies are full of them.
        val stubParent = spineId
        elements += UiElement(
            id = "stub_search",
            role = ElementRole.CONTAINER,
            bounds = Bounds(0, 296, 1080, 296),
            className = "android.view.ViewStub",
            resourceId = "com.android.settings:id/search_mode_stub",
            parentId = stubParent,
            depth = wrapperDepth,
        )

        val rowIds = (0 until rows).map { "row$it" }
        elements += Ui.list(
            "settings_list",
            Bounds(0, 300, 1080, 1800),
            rowIds,
            parentId = stubParent,
            depth = wrapperDepth,
        )
        rowIds.forEachIndexed { i, id ->
            elements += Ui.listItem(
                id = id,
                text = listOf("Network & internet", "Connected devices", "Apps", "Notifications")
                    .getOrElse(i) { "Setting ${i + 1}" },
                bounds = Bounds(0, 300 + i * 200, 1080, 500 + i * 200),
                parentId = "settings_list",
                depth = wrapperDepth + 1,
            )
        }

        // The spine's last wrapper owns both the stub and the list.
        val lastWrapper = elements.indexOfFirst { it.id == spineId }
        elements[lastWrapper] = elements[lastWrapper]
            .copy(childIds = listOf("stub_search", "settings_list"))

        return FakeScreen(
            packageName = "com.android.settings",
            windowTitle = "Settings",
            metrics = PHONE,
            elements = elements,
        )
    }

    /** A launcher-like screen whose [FakeScreen.launchHandler] can open the shop app. */
    public fun launcher(): FakeScreen {
        val screen = FakeScreen(
            packageName = "com.android.launcher",
            windowTitle = "Home",
            metrics = PHONE,
            elements = listOf(Ui.root("com.android.launcher", PHONE, emptyList())),
        )
        screen.launchHandler = { s, pkg ->
            if (pkg == "com.example.shop") {
                val target = home()
                s.packageName = target.packageName
                s.windowTitle = target.windowTitle
                s.replaceAll(target.elements)
                DriverOutcome.Ok
            } else {
                DriverOutcome.Rejected(
                    com.andropilot.core.driver.DriverErrorKind.APP_UNAVAILABLE,
                    "'$pkg' is not installed.",
                )
            }
        }
        return screen
    }
}
