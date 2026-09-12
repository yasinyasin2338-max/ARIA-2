package com.orbisai.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * User-enabled Android accessibility bridge.
 *
 * Remote control is available only while the owner has explicitly enabled this
 * Accessibility service and the visible ARIA foreground bridge. Lock screen,
 * password nodes, Secure Folder/Knox and banking/payment/authenticator apps are
 * hard-blocked by AriaSafetyPolicy.
 */
class AriaAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: AriaAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        private fun currentPackage(service: AriaAccessibilityService): String =
            service.rootInActiveWindow?.packageName?.toString().orEmpty()

        private fun interactionService(): AriaAccessibilityService? {
            val service = instance ?: return null
            if (AriaSafetyPolicy.state(service, currentPackage(service)) != null) return null
            return service
        }

        fun performApprovedAction(action: String): Boolean {
            val service = instance ?: return false
            if (AriaSafetyPolicy.isLocked(service)) return false
            val globalAction = when (action.uppercase()) {
                "HOME" -> GLOBAL_ACTION_HOME
                "BACK" -> GLOBAL_ACTION_BACK
                "RECENTS" -> GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
                "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return false
            }
            return service.performGlobalAction(globalAction)
        }

        fun tap(x: Int, y: Int): Boolean {
            val service = interactionService() ?: return false
            if (x < 0 || y < 0) return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
            val service = interactionService() ?: return false
            if (minOf(x1, y1, x2, y2) < 0) return false
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val duration = durationMs.coerceIn(100, 5000)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun typeText(text: String): Boolean {
            val service = interactionService() ?: return false
            if (text.isEmpty() || text.length > 4000) return false
            val root = service.rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: breadthFirst(root).firstOrNull { it.isEditable && it.isVisibleToUser }
                ?: return false
            if (target.isPassword) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun clearText(): Boolean {
            val service = interactionService() ?: return false
            val root = service.rootInActiveWindow ?: return false
            val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: breadthFirst(root).firstOrNull { it.isEditable && it.isVisibleToUser }
                ?: return false
            if (target.isPassword) return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        fun clickText(query: String): Boolean {
            val service = interactionService() ?: return false
            val q = query.trim().lowercase()
            if (q.isBlank() || q.length > 160) return false
            val root = service.rootInActiveWindow ?: return false
            val match = breadthFirst(root).firstOrNull { node ->
                if (!node.isVisibleToUser || node.isPassword) false
                else {
                    val t = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val d = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    t == q || d == q || t.contains(q) || d.contains(q)
                }
            } ?: return false
            var node: AccessibilityNodeInfo? = match
            repeat(6) {
                val n = node ?: return@repeat
                if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                node = n.parent
            }
            return false
        }

        fun scroll(forward: Boolean): Boolean {
            val service = interactionService() ?: return false
            val root = service.rootInActiveWindow ?: return false
            val node = breadthFirst(root).firstOrNull { it.isScrollable && it.isVisibleToUser } ?: return false
            return node.performAction(
                if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        }

        fun openApp(packageName: String): Boolean {
            val service = instance ?: return false
            if (AriaSafetyPolicy.isLocked(service)) return false
            if (!packageName.matches(Regex("[A-Za-z0-9_.]{3,180}"))) return false
            if (AriaSafetyPolicy.isProtectedPackage(service, packageName)) return false
            val launch = service.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { service.startActivity(launch); true }.getOrDefault(false)
        }

        fun openSettings(kind: String): Boolean {
            val service = instance ?: return false
            if (AriaSafetyPolicy.isLocked(service)) return false
            val action = when (kind.uppercase()) {
                "GENERAL" -> Settings.ACTION_SETTINGS
                "WIFI" -> Settings.ACTION_WIFI_SETTINGS
                "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "DISPLAY" -> Settings.ACTION_DISPLAY_SETTINGS
                "SOUND" -> Settings.ACTION_SOUND_SETTINGS
                "APPS" -> Settings.ACTION_APPLICATION_SETTINGS
                "NOTIFICATIONS" -> "android.settings.NOTIFICATION_SETTINGS"
                "ACCESSIBILITY" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                else -> return false
            }
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { service.startActivity(intent); true }.getOrDefault(false)
        }

        fun adjustVolume(direction: String): Boolean {
            val service = instance ?: return false
            if (AriaSafetyPolicy.isLocked(service)) return false
            val audio = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val adjust = when (direction.uppercase()) {
                "UP" -> AudioManager.ADJUST_RAISE
                "DOWN" -> AudioManager.ADJUST_LOWER
                "MUTE" -> AudioManager.ADJUST_MUTE
                "UNMUTE" -> AudioManager.ADJUST_UNMUTE
                else -> return false
            }
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
            return true
        }

        fun mediaKey(action: String): Boolean {
            val service = instance ?: return false
            if (AriaSafetyPolicy.isLocked(service)) return false
            val audio = service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val keyCode = when (action.uppercase()) {
                "PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "PREVIOUS" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                "STOP" -> KeyEvent.KEYCODE_MEDIA_STOP
                else -> return false
            }
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            return true
        }

        fun foregroundAppInfo(): String {
            val service = instance ?: return "ACCESSIBILITY_NOT_READY"
            if (AriaSafetyPolicy.isLocked(service)) return "PROTECTED_LOCK_SCREEN"
            val pkg = currentPackage(service)
            if (pkg.isBlank()) return "NO_ACTIVE_WINDOW"
            if (AriaSafetyPolicy.isProtectedPackage(service, pkg)) return "PROTECTED_APP"
            val label = runCatching {
                @Suppress("DEPRECATION")
                val info = service.packageManager.getApplicationInfo(pkg, 0)
                service.packageManager.getApplicationLabel(info).toString()
            }.getOrDefault("")
            return "package=${clean(pkg)}|label=${clean(label)}"
        }

        fun visibleUiSnapshot(): String {
            val service = instance ?: return "ACCESSIBILITY_NOT_READY"
            val root = service.rootInActiveWindow ?: return "NO_ACTIVE_WINDOW"
            AriaSafetyPolicy.state(service, root.packageName?.toString())?.let { return it }
            val out = StringBuilder()
            var count = 0
            val queue: ArrayDeque<Pair<AccessibilityNodeInfo, Int>> = ArrayDeque()
            queue.add(root to 0)
            while (queue.isNotEmpty() && count < 120 && out.length < 11000) {
                val (node, depth) = queue.removeFirst()
                if (node.isVisibleToUser) {
                    val r = Rect(); node.getBoundsInScreen(r)
                    val secure = node.isPassword
                    val text = if (secure) "[secure]" else clean(node.text?.toString())
                    val desc = if (secure) "[secure]" else clean(node.contentDescription?.toString())
                    val cls = clean(node.className?.toString())
                    val id = clean(node.viewIdResourceName)
                    if (text.isNotBlank() || desc.isNotBlank() || node.isClickable || node.isEditable) {
                        out.append(count).append('|')
                            .append("d=").append(depth.coerceAtMost(20)).append('|')
                            .append("class=").append(cls.take(80)).append('|')
                            .append("text=").append(text.take(220)).append('|')
                            .append("desc=").append(desc.take(220)).append('|')
                            .append("id=").append(id.take(160)).append('|')
                            .append("click=").append(if (node.isClickable) 1 else 0).append('|')
                            .append("edit=").append(if (node.isEditable && !secure) 1 else 0).append('|')
                            .append("bounds=").append(r.left).append(',').append(r.top).append(',').append(r.right).append(',').append(r.bottom)
                            .append('\n')
                        count++
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
            return out.toString().ifBlank { "NO_VISIBLE_ACCESSIBLE_NODES" }
        }

        private fun breadthFirst(root: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque(); q.add(root)
            var seen = 0
            while (q.isNotEmpty() && seen < 400) {
                val n = q.removeFirst(); seen++; yield(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
            }
        }

        private fun clean(value: String?): String = value.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('|', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
