package com.nextgen.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.provider.Settings;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * System buttons (Home/Back/...) + "inside any app" control: read the screen, search, open the Nth item,
 * click by text, type, scroll. Every ui* method BLOCKS (polls up to a few seconds) - call from a background thread.
 */
public class AgentAccessibilityService extends AccessibilityService {
    public static AgentAccessibilityService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    public static boolean isEnabled(Context ctx) {
        if (instance != null) return true;
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            int enabled = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services != null) {
                String target = ctx.getPackageName() + "/" + AgentAccessibilityService.class.getName();
                String targetShort = ctx.getPackageName() + "/." + AgentAccessibilityService.class.getSimpleName();
                return services.contains(target) || services.contains(targetShort);
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean autoEnable(Context ctx) {
        if (isEnabled(ctx)) return true;
        String serviceName = ctx.getPackageName() + "/" + AgentAccessibilityService.class.getName();
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated = (current == null || current.isEmpty()) ? serviceName : (current + ":" + serviceName);
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            if (isEnabled(ctx)) return true;
        } catch (Exception ignored) {}

        try {
            Process p = Runtime.getRuntime().exec("su");
            OutputStream os = p.getOutputStream();
            String cmd = "settings put secure enabled_accessibility_services " + serviceName +
                    " && settings put secure accessibility_enabled 1\nexit\n";
            os.write(cmd.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            p.waitFor();
            if (p.exitValue() == 0) {
                return isEnabled(ctx);
            }
        } catch (Exception ignored) {}

        return isEnabled(ctx);
    }

    public static boolean perform(int globalAction) {
        return instance != null && instance.performGlobalAction(globalAction);
    }

    // ------------------------------------------------------------------ public UI actions (return "OK" / "ERR:...")
    private static final String[] SEARCH_WORDS = {"search", "find", "thedu", "theduga", "தேடு", "தேடல்", "தேடுங்கள்", "தேடுதல்", "検索", "query"};

    public String uiSearch(String query, boolean submit) {
        AccessibilityNodeInfo root = waitRoot(3000);
        if (root == null) return "ERR:cannot read the screen";
        if (isOwnApp(root)) return "ERR:open the app first";
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) {
            AccessibilityNodeInfo trigger = findSearchTrigger(root);
            if (trigger == null) return "ERR:search box not found";
            click(trigger);
            for (int i = 0; i < 15 && edit == null; i++) {      // wait up to ~3 s for the search field
                sleep(200);
                root = getRootInActiveWindow();
                if (root != null) edit = findEditable(root);
            }
            if (edit == null) return "ERR:search box did not open";
        }
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        edit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!setText(edit, query)) return "ERR:cannot type in search box";
        if (submit) {
            sleep(500);
            edit.refresh();
            edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        sleep(1500);                                              // let results load
        return "OK";
    }

    public String uiOpenNth(int n) {
        if (n < 1) return "ERR:bad number";
        List<AccessibilityNodeInfo> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {                            // wait up to ~4 s for the list
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                items = listItems(root);
                if (items.size() >= n) break;
            }
            sleep(200);
        }
        if (items.size() < n) return "ERR:item " + n + " not found";
        return click(items.get(n - 1)) ? "OK" : "ERR:cannot open item " + n;
    }

    public String uiClick(String text) {
        String q = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return "ERR:nothing to click";
        for (int i = 0; i < 15; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                AccessibilityNodeInfo hit = findByLabel(root, q, true);
                if (hit == null) hit = findByLabel(root, q, false);
                if (hit != null) return click(hit) ? "OK" : "ERR:cannot click " + text;
            }
            sleep(200);
        }
        return "ERR:not found on screen: " + text;
    }

    public String uiType(String text, boolean send) {
        AccessibilityNodeInfo root = waitRoot(3000);
        if (root == null) return "ERR:cannot read the screen";
        if (isOwnApp(root)) return "ERR:open the app first";
        AccessibilityNodeInfo edit = findEditable(root);
        if (edit == null) return "ERR:no text box on screen";
        edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        if (!setText(edit, text)) return "ERR:cannot type";
        if (send) {
            sleep(300);
            String r = uiClick("send");
            if (!"OK".equals(r)) edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return "OK";
    }

    public String uiScroll(boolean down) {
        AccessibilityNodeInfo root = waitRoot(3000);
        if (root == null) return "ERR:cannot read the screen";
        if (isOwnApp(root)) return "ERR:open the app first";
        AccessibilityNodeInfo list = biggestScrollable(root);
        if (list == null) return "ERR:nothing to scroll";
        return list.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ? "OK" : "ERR:cannot scroll";
    }

    // ------------------------------------------------------------------ helpers
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private boolean isOwnApp(AccessibilityNodeInfo root) {
        return root.getPackageName() != null && getPackageName().contentEquals(root.getPackageName());
    }

    private AccessibilityNodeInfo waitRoot(long ms) {
        long end = System.currentTimeMillis() + ms;
        do {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null) return r;
            sleep(150);
        } while (System.currentTimeMillis() < end);
        return null;
    }

    private static String label(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        if (n.getText() != null) sb.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append(' ');
        if (n.getViewIdResourceName() != null) sb.append(n.getViewIdResourceName());
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String ownText(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        if (n.getText() != null) sb.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription());
        return sb.toString().toLowerCase(Locale.ROOT).trim();
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }

    private static List<AccessibilityNodeInfo> all(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo first = null;
        for (AccessibilityNodeInfo n : all(root)) {
            if (n.isEditable() && n.isVisibleToUser()) {
                if (n.isFocused()) return n;
                if (first == null) first = n;
            }
        }
        return first;
    }

    private AccessibilityNodeInfo findSearchTrigger(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo n : all(root)) {
            if (n.isEditable() || !n.isVisibleToUser()) continue;
            String l = label(n);
            for (String w : SEARCH_WORDS) {
                if (l.contains(w) && clickableSelfOrParent(n) != null) return n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findByLabel(AccessibilityNodeInfo root, String q, boolean exact) {
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isVisibleToUser()) continue;
            String t = ownText(n);
            if (t.isEmpty()) continue;
            if (exact ? t.equals(q) : t.contains(q)) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo biggestScrollable(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo best = null;
        long bestArea = -1;
        Rect r = new Rect();
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isScrollable() || !n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (area > bestArea) { bestArea = area; best = n; }
        }
        return best;
    }

    private static AccessibilityNodeInfo clickableSelfOrParent(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; c != null && i < 6; i++) {
            if (c.isClickable()) return c;
            c = c.getParent();
        }
        return null;
    }

    private static boolean hasClickable(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo x : all(n)) if (x.isClickable()) return true;
        return false;
    }

    /** The visible, tappable rows of the main list on screen (chats, search results, videos...), top to bottom. */
    private List<AccessibilityNodeInfo> listItems(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo container = biggestScrollable(root);
        List<AccessibilityNodeInfo> items = new ArrayList<>();
        if (container == null) return items;
        for (int i = 0; i < container.getChildCount(); i++) {
            AccessibilityNodeInfo ch = container.getChild(i);
            if (ch == null || !ch.isVisibleToUser()) continue;
            if (findEditable(ch) != null) continue;                // skip the search bar row
            if (!ch.isClickable() && !hasClickable(ch)) continue;   // skip section headers ("Chats", ...)
            Rect b = new Rect();
            ch.getBoundsInScreen(b);
            if (b.height() < 40) continue;
            items.add(ch);
        }
        Collections.sort(items, new Comparator<AccessibilityNodeInfo>() {
            @Override public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect(), rb = new Rect();
                a.getBoundsInScreen(ra); b.getBoundsInScreen(rb);
                return Integer.compare(ra.top, rb.top);
            }
        });
        return items;
    }

    private boolean setText(AccessibilityNodeInfo edit, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Click via the accessibility action; if the app ignores it, tap the centre of the item with a gesture. */
    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = clickableSelfOrParent(n);
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }
}
