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
    public static volatile boolean autoConfirmUninstall = false;
    public static volatile String uninstallPackageName = "";

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (autoConfirmUninstall) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    if (tryClickUninstallConfirm(root, uninstallPackageName)) {
                        autoConfirmUninstall = false;
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    @Override public void onInterrupt() { }

    public static void armAutoConfirmUninstall(final String pkg) {
        autoConfirmUninstall = true;
        uninstallPackageName = (pkg == null) ? "" : pkg;
        new Thread(new Runnable() {
            @Override public void run() {
                long start = System.currentTimeMillis();
                while (autoConfirmUninstall && (System.currentTimeMillis() - start < 12000)) {
                    if (instance != null) {
                        try {
                            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                            if (root != null) {
                                if (instance.tryClickUninstallConfirm(root, uninstallPackageName)) {
                                    autoConfirmUninstall = false;
                                    break;
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    try { Thread.sleep(120); } catch (InterruptedException ignored) {}
                }
                autoConfirmUninstall = false;
            }
        }).start();
    }

    private static final String[] UNINSTALL_CONFIRM_TEXTS = {
        "ok", "uninstall", "delete", "remove",
        "சரி", "அகற்று", "நீக்கு", "அன்இன்ஸ்டால்",
        "アンインストール", "削除",
        "ठीक है", "अनइंस्टॉल"
    };

    public boolean tryClickUninstallConfirm(AccessibilityNodeInfo root, String targetPackage) {
        if (root == null) return false;
        try {
            // 1. Try standard positive button ID (android:id/button1)
            List<AccessibilityNodeInfo> btn1List = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            if (btn1List != null && !btn1List.isEmpty()) {
                for (AccessibilityNodeInfo b : btn1List) {
                    if (b != null && b.isVisibleToUser()) {
                        return click(b);
                    }
                }
            }

            // 2. Try known package installer OK/Uninstall button IDs
            String[] ids = {
                "com.android.packageinstaller:id/ok_button",
                "com.google.android.packageinstaller:id/ok_button",
                "com.android.packageinstaller:id/btn_ok",
                "com.samsung.android.packageinstaller:id/ok_button",
                "com.miui.packageinstaller:id/ok_button"
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
                if (list != null && !list.isEmpty()) {
                    for (AccessibilityNodeInfo b : list) {
                        if (b != null && b.isVisibleToUser()) {
                            return click(b);
                        }
                    }
                }
            }

            // 3. Scan nodes for confirmation button text (e.g. "OK", "Uninstall", "சரி")
            List<AccessibilityNodeInfo> allNodes = all(root);
            for (AccessibilityNodeInfo node : allNodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                String t = ownText(node);
                if (t.isEmpty()) continue;
                for (String w : UNINSTALL_CONFIRM_TEXTS) {
                    if (t.equals(w) || (t.contains(w) && !t.contains("cancel") && !t.contains("ரத்து") && !t.contains("no"))) {
                        CharSequence cls = node.getClassName();
                        String className = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
                        if (className.contains("button") || node.isClickable() || clickableSelfOrParent(node) != null) {
                            return click(node);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

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

    public String uiClearText() {
        for (int i = 0; i < 15; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                AccessibilityNodeInfo edit = findEditable(root);
                if (edit != null) {
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    Bundle args = new Bundle();
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                    if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        return "OK";
                    }
                }
            }
            sleep(150);
        }
        return "ERR:no active text field to clear";
    }

    public String uiOpenLastFile() {
        for (int i = 0; i < 20; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                List<AccessibilityNodeInfo> fileNodes = new ArrayList<>();

                // 1. WhatsApp specific document & file holder IDs
                String[] fileIds = {
                    "com.whatsapp:id/document_holder",
                    "com.whatsapp:id/document_title",
                    "com.whatsapp:id/document_thumbnail",
                    "com.whatsapp:id/document_file_name",
                    "com.whatsapp:id/image_holder",
                    "com.whatsapp:id/thumb",
                    "com.whatsapp:id/video_thumb",
                    "com.whatsapp:id/media_container",
                    "com.whatsapp:id/audio_holder",
                    "com.whatsapp:id/play_pause_button",
                    "com.whatsapp:id/icon_action"
                };

                for (String fId : fileIds) {
                    List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(fId);
                    if (found != null && !found.isEmpty()) {
                        for (AccessibilityNodeInfo n : found) {
                            if (n != null && n.isVisibleToUser()) {
                                AccessibilityNodeInfo target = clickableSelfOrParent(n);
                                if (target != null && !fileNodes.contains(target)) {
                                    fileNodes.add(target);
                                }
                            }
                        }
                    }
                }

                // 2. Scan all nodes for file extensions or file descriptions
                List<AccessibilityNodeInfo> allNodes = all(root);
                for (AccessibilityNodeInfo n : allNodes) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    if (n.isEditable() || isExcludedHeaderOrNav(n)) continue;

                    String txt = ownText(n);
                    String l = label(n);
                    boolean isFile = false;

                    if (txt.contains(".pdf") || txt.contains(".doc") || txt.contains(".docx") ||
                        txt.contains(".xls") || txt.contains(".xlsx") || txt.contains(".zip") ||
                        txt.contains(".rar") || txt.contains(".apk") || txt.contains(".txt") ||
                        txt.contains(".mp4") || txt.contains(".mp3") || txt.contains(".jpg") || txt.contains(".png")) {
                        isFile = true;
                    }

                    if (l.contains("pdf") || l.contains("document") || l.contains("file") ||
                        l.contains("photo") || l.contains("video") || l.contains("audio message") ||
                        l.contains("voice message")) {
                        if (!l.contains("camera") && !l.contains("attach") && !l.contains("send") && !l.contains("emoji")) {
                            isFile = true;
                        }
                    }

                    if (isFile) {
                        AccessibilityNodeInfo target = clickableSelfOrParent(n);
                        if (target != null && !fileNodes.contains(target)) {
                            fileNodes.add(target);
                        }
                    }
                }

                if (!fileNodes.isEmpty()) {
                    sortItems(fileNodes);
                    // The LAST received file in chat is at the bottom of the list
                    AccessibilityNodeInfo lastFile = fileNodes.get(fileNodes.size() - 1);
                    if (click(lastFile)) {
                        return "OK";
                    }
                }
            }
            sleep(150);
        }
        return "ERR:no file found in chat";
    }

    public String uiClick(String text) {
        String q = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty()) return "ERR:nothing to click";

        // Specialized robust Send button handler for WhatsApp, Instagram, Messages, Telegram
        if ("send".equals(q) || "அனுப்பு".equals(q) || "அனுப்பி விடு".equals(q) || "anuppu".equals(q)) {
            for (int i = 0; i < 20; i++) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && !isOwnApp(root)) {
                    // 1. WhatsApp send button
                    List<AccessibilityNodeInfo> waList = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (waList != null && !waList.isEmpty()) {
                        for (AccessibilityNodeInfo b : waList) {
                            if (b != null && b.isVisibleToUser()) {
                                return click(b) ? "OK" : "ERR:cannot click WhatsApp send button";
                            }
                        }
                    }

                    // 2. Instagram send button IDs
                    String[] igIds = {
                        "com.instagram.android:id/row_thread_composer_send_button_container",
                        "com.instagram.android:id/row_thread_composer_button_send",
                        "com.instagram.android:id/direct_text_send_button"
                    };
                    for (String igId : igIds) {
                        List<AccessibilityNodeInfo> igList = root.findAccessibilityNodeInfosByViewId(igId);
                        if (igList != null && !igList.isEmpty()) {
                            for (AccessibilityNodeInfo b : igList) {
                                if (b != null && b.isVisibleToUser()) {
                                    return click(b) ? "OK" : "ERR:cannot click Instagram send button";
                                }
                            }
                        }
                    }

                    // 3. Messages / Telegram / Standard SMS send button IDs
                    String[] sIds = {
                        "com.google.android.apps.messaging:id/send_message_button_icon",
                        "com.google.android.apps.messaging:id/send_message_button",
                        "org.telegram.messenger:id/send_button",
                        "com.android.mms:id/send_button"
                    };
                    for (String sId : sIds) {
                        List<AccessibilityNodeInfo> sList = root.findAccessibilityNodeInfosByViewId(sId);
                        if (sList != null && !sList.isEmpty()) {
                            for (AccessibilityNodeInfo b : sList) {
                                if (b != null && b.isVisibleToUser()) {
                                    return click(b) ? "OK" : "ERR:cannot click send button";
                                }
                            }
                        }
                    }

                    // 4. By contentDescription or label matching "send" / "அனுப்பு" / "送信"
                    for (AccessibilityNodeInfo n : all(root)) {
                        if (!n.isVisibleToUser()) continue;
                        String l = label(n);
                        if (l.contains("send") || l.contains("அனுப்பு") || l.contains("送信")) {
                            if (!l.contains("cancel") && !l.contains("resend") && !l.contains("sending") && !l.contains("money")) {
                                AccessibilityNodeInfo target = clickableSelfOrParent(n);
                                if (target != null) {
                                    return click(target) ? "OK" : "ERR:cannot click send button";
                                }
                            }
                        }
                    }

                    // 5. Fallback: IME enter on editable message field
                    AccessibilityNodeInfo edit = findEditable(root);
                    if (edit != null) {
                        if (edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                            return "OK";
                        }
                    }
                }
                sleep(150);
            }
            return "ERR:send button not found";
        }

        // General text search for click
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
            if (!n.isVisibleToUser()) continue;
            boolean isScroll = n.isScrollable();
            CharSequence cn = n.getClassName();
            String className = cn == null ? "" : cn.toString().toLowerCase(Locale.ROOT);
            boolean isListClass = className.contains("recycler") || className.contains("listview") ||
                    className.contains("scrollview") || className.contains("gridview") ||
                    className.contains("viewpager");
            if (!isScroll && !isListClass) continue;

            n.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            if (area > bestArea && r.height() > 150) {
                bestArea = area;
                best = n;
            }
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

    private static AccessibilityNodeInfo findClickableChild(AccessibilityNodeInfo n) {
        if (n == null) return null;
        for (AccessibilityNodeInfo ch : all(n)) {
            if (ch != n && ch.isClickable() && ch.isVisibleToUser()) {
                return ch;
            }
        }
        return null;
    }

    private static boolean hasClickable(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo x : all(n)) if (x.isClickable()) return true;
        return false;
    }

    private static final String[] EXCLUDE_NAV_WORDS = {
        "search", "thedu", "தேடு", "menu", "more options", "overflow",
        "camera", "chats", "updates", "status", "calls", "communities",
        "tab", "navigation", "navigate up", "back", "settings", "filter",
        "new chat", "new call", "create", "voice search", "audio search",
        "cast", "notifications", "unread", "archived", "starred messages"
    };

    private boolean isExcludedHeaderOrNav(AccessibilityNodeInfo n) {
        if (n == null) return false;
        String vId = n.getViewIdResourceName();
        String id = vId == null ? "" : vId.toLowerCase(Locale.ROOT);
        if (id.contains("search") || id.contains("menuitem") || id.contains("camera") ||
            id.contains("toolbar") || id.contains("action_bar") || id.contains("tab_layout") ||
            id.contains("header_container") || id.contains("search_src_text") ||
            id.contains("search_button") || id.contains("menu_item") || id.contains("tab")) {
            return true;
        }
        if (n.isEditable() || findEditable(n) != null) return true;

        String l = label(n).trim();
        for (String ex : EXCLUDE_NAV_WORDS) {
            if (l.equals(ex) || l.startsWith(ex + " ") || l.endsWith(" " + ex)) {
                return true;
            }
        }
        return false;
    }

    private static void sortItems(List<AccessibilityNodeInfo> items) {
        Collections.sort(items, new Comparator<AccessibilityNodeInfo>() {
            @Override public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect(), rb = new Rect();
                a.getBoundsInScreen(ra); b.getBoundsInScreen(rb);
                if (Math.abs(ra.top - rb.top) > 30) {
                    return Integer.compare(ra.top, rb.top);
                }
                return Integer.compare(ra.left, rb.left);
            }
        });
    }

    /** The visible, tappable rows/cards of the main list on screen (chats, search results, videos, shelves...), top to bottom. */
    private List<AccessibilityNodeInfo> listItems(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> items = new ArrayList<>();
        CharSequence rootPkgSeq = root.getPackageName();
        String rootPkg = rootPkgSeq == null ? "" : rootPkgSeq.toString().toLowerCase(Locale.ROOT);

        // 1. WhatsApp Fast Path: detect actual chat rows directly
        if (rootPkg.contains("whatsapp")) {
            List<AccessibilityNodeInfo> waRows = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contact_row_container");
            if (waRows != null && !waRows.isEmpty()) {
                List<AccessibilityNodeInfo> visibleWa = new ArrayList<>();
                for (AccessibilityNodeInfo row : waRows) {
                    if (row != null && row.isVisibleToUser() && (row.isClickable() || hasClickable(row))) {
                        visibleWa.add(row);
                    }
                }
                if (!visibleWa.isEmpty()) {
                    sortItems(visibleWa);
                    return visibleWa;
                }
            }

            List<AccessibilityNodeInfo> waNames = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
            if (waNames != null && !waNames.isEmpty()) {
                List<AccessibilityNodeInfo> visibleWa = new ArrayList<>();
                for (AccessibilityNodeInfo nameNode : waNames) {
                    if (nameNode != null && nameNode.isVisibleToUser()) {
                        AccessibilityNodeInfo parent = clickableSelfOrParent(nameNode);
                        if (parent != null && !visibleWa.contains(parent)) {
                            visibleWa.add(parent);
                        }
                    }
                }
                if (!visibleWa.isEmpty()) {
                    sortItems(visibleWa);
                    return visibleWa;
                }
            }
        }

        // 2. Instagram Fast Path: detect direct inbox thread rows directly
        if (rootPkg.contains("instagram")) {
            List<AccessibilityNodeInfo> igRows = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_inbox_container");
            if (igRows != null && !igRows.isEmpty()) {
                List<AccessibilityNodeInfo> visibleIg = new ArrayList<>();
                for (AccessibilityNodeInfo row : igRows) {
                    if (row != null && row.isVisibleToUser() && (row.isClickable() || hasClickable(row))) {
                        visibleIg.add(row);
                    }
                }
                if (!visibleIg.isEmpty()) {
                    sortItems(visibleIg);
                    return visibleIg;
                }
            }
        }

        // 3. Chrome / Browser / Google Search Fast Path: detect actual website search results
        if (rootPkg.contains("chrome") || rootPkg.contains("browser") || rootPkg.contains("googlequicksearchbox")) {
            List<AccessibilityNodeInfo> webResults = new ArrayList<>();
            List<AccessibilityNodeInfo> allNodes = all(root);
            Rect scr = new Rect();
            for (AccessibilityNodeInfo n : allNodes) {
                if (n == null || !n.isVisibleToUser() || n.isEditable() || isExcludedHeaderOrNav(n)) continue;
                n.getBoundsInScreen(scr);
                // In Chrome / Google Search results, top < 260px contains URL bar, search input, and category tabs (All, Images, etc.)
                if (scr.top < 260 || scr.height() < 35 || scr.width() < 140) continue;
                String txt = ownText(n);
                if (txt.isEmpty() || txt.length() < 3) continue;
                String lower = txt.toLowerCase(Locale.ROOT);
                if (lower.equals("all") || lower.equals("images") || lower.equals("news") || lower.equals("videos") ||
                    lower.equals("maps") || lower.equals("shopping") || lower.equals("search") || lower.equals("google")) continue;
                AccessibilityNodeInfo target = clickableSelfOrParent(n);
                if (target != null && !webResults.contains(target)) {
                    webResults.add(target);
                }
            }
            if (!webResults.isEmpty()) {
                sortItems(webResults);
                List<AccessibilityNodeInfo> distinctResults = new ArrayList<>();
                for (AccessibilityNodeInfo cand : webResults) {
                    Rect r = new Rect();
                    cand.getBoundsInScreen(r);
                    boolean overlap = false;
                    for (AccessibilityNodeInfo kept : distinctResults) {
                        Rect kr = new Rect();
                        kept.getBoundsInScreen(kr);
                        if (Math.abs(r.top - kr.top) < 25) {
                            overlap = true;
                            break;
                        }
                    }
                    if (!overlap) {
                        distinctResults.add(cand);
                    }
                }
                if (!distinctResults.isEmpty()) {
                    return distinctResults;
                }
                return webResults;
            }
        }

        AccessibilityNodeInfo container = biggestScrollable(root);
        Rect bounds = new Rect();

        if (container != null && container.getChildCount() > 0) {
            for (int i = 0; i < container.getChildCount(); i++) {
                AccessibilityNodeInfo ch = container.getChild(i);
                if (ch == null || !ch.isVisibleToUser()) continue;
                if (isExcludedHeaderOrNav(ch)) continue;

                // Check if this child is a horizontal shelf / collection (e.g. YouTube video shelf, carousel)
                CharSequence chClass = ch.getClassName();
                String chClassName = chClass == null ? "" : chClass.toString().toLowerCase(Locale.ROOT);
                boolean isShelf = ch.isScrollable() || chClassName.contains("recycler") || chClassName.contains("scroll");

                int subCount = ch.getChildCount();
                if ((isShelf || subCount >= 2) && subCount > 0) {
                    List<AccessibilityNodeInfo> subItems = new ArrayList<>();
                    for (int j = 0; j < subCount; j++) {
                        AccessibilityNodeInfo sub = ch.getChild(j);
                        if (sub != null && sub.isVisibleToUser() && !isExcludedHeaderOrNav(sub)) {
                            sub.getBoundsInScreen(bounds);
                            if (bounds.height() >= 40 && bounds.width() >= 60 && (sub.isClickable() || hasClickable(sub))) {
                                subItems.add(sub);
                            }
                        }
                    }
                    if (subItems.size() >= 2) {
                        items.addAll(subItems);
                        continue;
                    }
                }

                if (!ch.isClickable() && !hasClickable(ch)) continue; // skip decorative dividers
                ch.getBoundsInScreen(bounds);
                if (bounds.height() < 40) continue;
                items.add(ch);
            }
        }

        // FALLBACK: If container has fewer than 2 items
        if (items.size() < 2) {
            List<AccessibilityNodeInfo> allNodes = all(root);
            Rect scrBounds = new Rect();
            for (AccessibilityNodeInfo n : allNodes) {
                if (!n.isVisibleToUser()) continue;
                if (isExcludedHeaderOrNav(n)) continue;
                n.getBoundsInScreen(scrBounds);
                // Exclude status bar and header (top < 160px)
                if (scrBounds.top < 160 || scrBounds.height() < 45 || scrBounds.width() < 120) continue;

                // Must be clickable or contain clickable element
                if (!n.isClickable() && !hasClickable(n)) continue;

                // Must have text, contentDescription, or substantial card area
                String txt = ownText(n);
                if (txt.isEmpty() && n.getChildCount() == 0) continue;

                // Check if this node is an ancestor or descendant of already collected item to avoid duplicates
                boolean duplicate = false;
                for (AccessibilityNodeInfo existing : items) {
                    Rect exRect = new Rect();
                    existing.getBoundsInScreen(exRect);
                    if (Math.abs(scrBounds.top - exRect.top) < 15 && Math.abs(scrBounds.bottom - exRect.bottom) < 15) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    items.add(n);
                }
            }
        }

        sortItems(items);
        return items;
    }

    private boolean setText(AccessibilityNodeInfo edit, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    /** Click via the accessibility action; if the app ignores it, tap the centre of the item with a gesture. */
    private boolean click(AccessibilityNodeInfo n) {
        if (n == null) return false;
        // 1. Try clickable self or parent
        AccessibilityNodeInfo c = clickableSelfOrParent(n);
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;

        // 2. Try clickable child inside n (e.g. video title, thumbnail, card root)
        AccessibilityNodeInfo child = findClickableChild(n);
        if (child != null && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;

        // 3. Physical gesture tap at the center of the item (reliable fallback for custom views)
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build();
        return dispatchGesture(g, null, null);
    }
}
