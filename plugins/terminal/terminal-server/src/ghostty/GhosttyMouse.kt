package org.example.kotlineditor.terminal.server.ghostty

import io.github.vlaaad.ghostty.bindings.ghostty_vt_h
import org.example.kotlineditor.model.TerminalMouseAction
import org.example.kotlineditor.model.TerminalMouseButton
import org.example.kotlineditor.model.TerminalMouseEvent

/** Mapping from the portable mouse event to the ids and modifiers of libghostty-vt. */
internal object GhosttyMouse {
    // The wheel is the fourth and fifth button, and the tilt the sixth and seventh, like xterm reports them.
    fun button(button: TerminalMouseButton): Int =
        when (button) {
            TerminalMouseButton.Left -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_LEFT()
            TerminalMouseButton.Middle -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_MIDDLE()
            TerminalMouseButton.Right -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_RIGHT()
            TerminalMouseButton.WheelUp -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_FOUR()
            TerminalMouseButton.WheelDown -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_FIVE()
            TerminalMouseButton.WheelLeft -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_SIX()
            TerminalMouseButton.WheelRight -> ghostty_vt_h.GHOSTTY_MOUSE_BUTTON_SEVEN()
        }

    fun action(event: TerminalMouseEvent): Int =
        when (event.action) {
            TerminalMouseAction.Press -> ghostty_vt_h.GHOSTTY_MOUSE_ACTION_PRESS()
            TerminalMouseAction.Release -> ghostty_vt_h.GHOSTTY_MOUSE_ACTION_RELEASE()
            TerminalMouseAction.Move -> ghostty_vt_h.GHOSTTY_MOUSE_ACTION_MOTION()
        }

    fun mods(event: TerminalMouseEvent): Int {
        var mods = 0
        if (event.shift) mods = mods or ghostty_vt_h.GHOSTTY_MODS_SHIFT()
        if (event.control) mods = mods or ghostty_vt_h.GHOSTTY_MODS_CTRL()
        if (event.alt) mods = mods or ghostty_vt_h.GHOSTTY_MODS_ALT()
        return mods
    }
}
