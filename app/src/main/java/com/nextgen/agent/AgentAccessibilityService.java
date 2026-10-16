package com.nextgen.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * System buttons (Home/Back/...) + "inside any app" control: read the screen, search, open the Nth item,
 * click by text, type, scroll. Every ui* method BLOCKS (polls up to a few seconds) - call from a background thread.
 */
public class AgentAccessibilityService extends AccessibilityService {
    public static AgentAccessibilityService instance;
    public static volatile boolean autoConfirmUninstall = false;
    public static volatile String uninstallPackageName = "";

    @Override
    protected void onServiceConnected() {
        instance = this;
        try {
            EarToCallManager.Companion.getInstance(this).onBoot(this);
        } catch (Exception ignored) {}
    }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (autoConfirmUninstall) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    if (tryClickUninstallConfirm(root, uninstallPackageName)) {
                        autoConfirmUninstall = false;
                        return;
                    }
                }
                AccessibilityNodeInfo src = event.getSource();
                if (src != null) {
                    if (tryClickUninstallConfirm(src, uninstallPackageName)) {
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
                while (autoConfirmUninstall && (System.currentTimeMillis() - start < 15000)) {
                    if (instance != null) {
                        try {
                            AccessibilityNodeInfo root = instance.getRootInActiveWindow();
                            if (root != null) {
                                if (instance.tryClickUninstallConfirm(root, uninstallPackageName)) {
                                    autoConfirmUninstall = false;
                                    break;
                                }
                            }
                            List<AccessibilityWindowInfo> windows = instance.getWindows();
                            if (windows != null) {
                                for (AccessibilityWindowInfo w : windows) {
                                    if (w == null) continue;
                                    AccessibilityNodeInfo wRoot = w.getRoot();
                                    if (wRoot != null) {
                                        if (instance.tryClickUninstallConfirm(wRoot, uninstallPackageName)) {
                                            autoConfirmUninstall = false;
                                            break;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
                autoConfirmUninstall = false;
            }
        }).start();
    }

    private boolean isUninstallerWindow(AccessibilityNodeInfo root, String targetPackage) {
        if (root == null) return false;
        CharSequence pSeq = root.getPackageName();
        if (pSeq == null) return false;
        String pkg = pSeq.toString().toLowerCase(Locale.ROOT);

        // Never click anything if the active window is our own app or launcher / home screen
        if (pkg.equals(targetPackage) || (instance != null && pkg.equals(instance.getPackageName()))) {
            return false;
        }
        if (pkg.contains("launcher") || pkg.contains("home") || pkg.contains("quickstep") ||
            pkg.contains("trebuchet") || pkg.contains("nexuslauncher")) {
            return false;
        }

        // Known package installer / system prompt packages
        if (pkg.contains("packageinstaller") || pkg.contains("installer") ||
            pkg.equals("android") || pkg.contains("permissioncontroller") ||
            pkg.contains("settings") || pkg.contains("securitycenter")) {
            return true;
        }

        // Fallback: check if the window contains explicit uninstallation question prompt
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null) continue;
            String t = ownText(n);
            if (t.contains("uninstall") || t.contains("நிறுவல் நீக்க") || t.contains("நீக்கவா") ||
                t.contains("அன்இன்ஸ்டால்") || t.contains("アンインストール") || t.contains("अनइंस्टॉल")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUninstallConfirmWord(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.trim().toLowerCase(Locale.ROOT);

        // Exclude cancel / negative words across English, Tamil, Japanese, Hindi
        if (t.contains("cancel") || t.contains("ரத்து") || t.contains("வேண்டாம்") ||
            t.contains("no") || t.contains("இல்லை") || t.contains("キャンセル") ||
            t.contains("いいえ") || t.contains("रद्द") || t.contains("नहीं")) {
            return false;
        }

        // Exclude question sentences or long descriptive labels (buttons are short)
        if (t.contains("?") || t.contains("want to") || t.contains("விரும்புகிறீர்களா") ||
            t.contains("ですか") || t.contains("चाहते") || t.length() > 25) {
            return false;
        }

        // Strict word boundary / exact matches for "ok" / "okay" (NEVER match substrings like "bookmyshow")
        if (t.equals("ok") || t.equals("okay") || t.equals("o.k.") || t.matches("^(ok|okay)[!.]?$")) {
            return true;
        }

        // Exact button matches across supported languages
        String[] confirmKeywords = {
            "uninstall", "delete", "remove",
            "uninstall app", "delete app",
            "சரி", "அகற்று", "நீக்கு", "அன்இன்ஸ்டால்", "நிறுவல் நீக்கு",
            "アンインストール", "削除",
            "ठीक है", "अनइंस्टॉल"
        };
        for (String w : confirmKeywords) {
            if (t.equals(w)) return true;
            if (w.length() > 3 && (t.startsWith(w) || t.equals(w))) return true;
        }
        return false;
    }

    public boolean tryClickUninstallConfirm(AccessibilityNodeInfo root, String targetPackage) {
        if (root == null) return false;
        if (!isUninstallerWindow(root, targetPackage)) return false;

        try {
            // 1. Try standard positive button ID (android:id/button1)
            List<AccessibilityNodeInfo> btn1List = root.findAccessibilityNodeInfosByViewId("android:id/button1");
            if (btn1List != null && !btn1List.isEmpty()) {
                for (AccessibilityNodeInfo b : btn1List) {
                    if (b != null && b.isVisibleToUser()) {
                        String t = ownText(b);
                        if (!t.contains("cancel") && !t.contains("ரத்து") && !t.contains("வேண்டாம்") && !t.contains("no")) {
                            return click(b);
                        }
                    }
                }
            }

            // 2. Try known package installer OK/Uninstall button IDs
            String[] ids = {
                "com.android.packageinstaller:id/ok_button",
                "com.google.android.packageinstaller:id/ok_button",
                "com.android.packageinstaller:id/btn_ok",
                "com.google.android.packageinstaller:id/btn_ok",
                "com.android.packageinstaller:id/button1",
                "com.google.android.packageinstaller:id/button1",
                "com.samsung.android.packageinstaller:id/ok_button",
                "com.miui.packageinstaller:id/ok_button",
                "com.miui.packageinstaller:id/btn_ok"
            };
            for (String id : ids) {
                List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
                if (list != null && !list.isEmpty()) {
                    for (AccessibilityNodeInfo b : list) {
                        if (b != null && b.isVisibleToUser()) {
                            String t = ownText(b);
                            if (!t.contains("cancel") && !t.contains("ரத்து") && !t.contains("வேண்டாம்") && !t.contains("no")) {
                                return click(b);
                            }
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
                if (isUninstallConfirmWord(t)) {
                    CharSequence cls = node.getClassName();
                    String className = cls == null ? "" : cls.toString().toLowerCase(Locale.ROOT);
                    if (className.contains("button") || node.isClickable() || clickableSelfOrParent(node) != null) {
                        return click(node);
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

    /**
     * Finds and triggers the call answer action / button across all phone dialers (Google, Samsung, Xiaomi, etc.)
     */
    public boolean answerIncomingCall() {
        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                // 1. Dialer specific answer button IDs
                String[] dialerIds = {
                    "com.google.android.dialer:id/answer_button",
                    "com.google.android.dialer:id/incall_first_button",
                    "com.samsung.android.incallui:id/answer_btn",
                    "com.samsung.android.incallui:id/voice_call_answer",
                    "com.android.incallui:id/answer_btn",
                    "com.android.incallui:id/answerButton",
                    "com.android.phone:id/answer_btn",
                    "com.coloros.incallui:id/answer_btn"
                };
                for (String dId : dialerIds) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(dId);
                    if (nodes != null && !nodes.isEmpty()) {
                        for (AccessibilityNodeInfo n : nodes) {
                            if (n != null && n.isVisibleToUser()) {
                                if (click(n)) return true;
                            }
                        }
                    }
                }

                // 2. Answer labels across supported languages
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    String l = label(n).toLowerCase(Locale.ROOT);
                    if (l.contains("decline") || l.contains("reject") || l.contains("cancel") || l.contains("ரத்து")) {
                        continue;
                    }
                    if (l.contains("answer") || l.contains("accept") || l.contains("ஏற்கவும்") ||
                        l.contains("பதில்") || l.contains("அழைப்பை ஏற்") || l.contains("応答")) {
                        AccessibilityNodeInfo target = clickableSelfOrParent(n);
                        if (target != null && click(target)) return true;
                    }
                }

                // 3. Fallback for swipe-to-answer dialers
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    String l = label(n).toLowerCase(Locale.ROOT);
                    if (l.contains("swipe up") || l.contains("drag to answer") || l.contains("slide to answer")) {
                        Rect r = new Rect();
                        n.getBoundsInScreen(r);
                        if (!r.isEmpty()) {
                            Path p = new Path();
                            p.moveTo(r.centerX(), r.centerY());
                            p.lineTo(r.centerX(), Math.max(100, r.centerY() - 400));
                            GestureDescription g = new GestureDescription.Builder()
                                .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build();
                            if (dispatchGesture(g, null, null)) return true;
                        }
                    }
                }
            }
            sleep(100);
        }
        return false;
    }

    // ------------------------------------------------------------------ public UI actions (return "OK" / "ERR:...")
    private static final String[] SEARCH_WORDS = {"search", "find", "thedu", "theduga", "தேடு", "தேடல்", "தேடுங்கள்", "தேடுதல்", "検索", "query"};

    private static boolean isAccountOrSettingsElement(AccessibilityNodeInfo n) {
        if (n == null) return false;
        String own = ownText(n);
        String vId = n.getViewIdResourceName();
        String idEntry = (vId != null && vId.contains(":id/")) ? vId.substring(vId.indexOf(":id/") + 4).toLowerCase(Locale.ROOT) : "";
        if (own.contains("account") || own.contains("profile") || own.contains("avatar") ||
            own.contains("sign in") || own.contains("signed in") || own.contains("google account") ||
            own.contains("manage your google account") || own.contains("கணக்கு") ||
            own.contains("அக்கவுண்ட்") || own.contains("அமைப்புகள்") ||
            idEntry.contains("account") || idEntry.contains("avatar") || idEntry.contains("profile") ||
            idEntry.contains("og_apd") || idEntry.contains("user_icon") || idEntry.contains("overflow") ||
            idEntry.contains("menu_item") || idEntry.contains("more_options")) {
            return true;
        }
        return false;
    }

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
            boolean enterSent = edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            if (!enterSent) {
                AccessibilityNodeInfo curRoot = getRootInActiveWindow();
                if (curRoot != null) {
                    for (AccessibilityNodeInfo n : all(curRoot)) {
                        if (n == null || !n.isVisibleToUser() || isAccountOrSettingsElement(n)) continue;
                        String l = label(n);
                        if ((l.contains("search") || l.contains("தேடு") || l.contains("go")) && (n.isClickable() || clickableSelfOrParent(n) != null)) {
                            click(n);
                            break;
                        }
                    }
                }
            }
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

        // General text search for click with smart target matching and auto-scroll retry
        for (int i = 0; i < 14; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                AccessibilityNodeInfo hit = findByLabel(root, q, true);
                if (hit == null) hit = findByLabel(root, q, false);
                if (hit == null) hit = findSmartTarget(root, q);
                if (hit != null) {
                    AccessibilityNodeInfo target = clickableSelfOrParent(hit);
                    if (target == null) target = hit;
                    if (click(target)) return "OK";
                }
            }
            if (i == 5) {
                // Auto retry: Scroll down to reveal offscreen targets
                uiScroll(true);
                sleep(300);
            } else {
                sleep(200);
            }
        }
        return "ERR:NOT_FOUND_RETRY:" + text;
    }

    public String uiToggleAnySwitch(Boolean turnOn) {
        for (int attempt = 0; attempt < 12; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                AccessibilityNodeInfo sw = findCheckableNode(root);
                if (sw != null) {
                    boolean current = sw.isChecked();
                    if (turnOn == null || current != turnOn) {
                        return click(sw) ? "OK" : "ERR:cannot toggle";
                    }
                    return "OK";
                }
            }
            sleep(180);
        }
        return "ERR:no switch found";
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

    // ------------------------------------------------------------------ Universal Cross-App Control
    /**
     * Swipes vertically with natural velocity for Reels, Shorts, and TikTok.
     * next = true -> swipes up to reveal next reel; next = false -> swipes down for previous reel.
     */
    public String uiSwipeReels(boolean next) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1080;
            int h = dm.heightPixels > 0 ? dm.heightPixels : 2400;

            float x = w / 2.0f;
            float startY = next ? (h * 0.78f) : (h * 0.22f);
            float endY = next ? (h * 0.18f) : (h * 0.82f);

            Path p = new Path();
            p.moveTo(x, startY);
            p.lineTo(x, endY);

            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 260))
                    .build();
            boolean ok = dispatchGesture(g, null, null);
            return ok ? "OK" : "ERR:gesture failed";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    /**
     * Identifies and types into the bottom message/comment composer of any app
     * (WhatsApp, Instagram, Messages, Telegram, YouTube comments, Twitter/X)
     * without confusing it with top search bars.
     */
    public String uiTypeComposer(String text, boolean send) {
        if (text == null) text = "";
        AccessibilityNodeInfo root = waitRoot(3000);
        if (root == null) return "ERR:cannot read the screen";
        if (isOwnApp(root)) return "ERR:open the app first";

        AccessibilityNodeInfo comp = findBottomComposer(root);
        if (comp == null) {
            // Check if there is an inactive placeholder button (e.g. "Message...", "Add a comment...")
            AccessibilityNodeInfo placeholder = findComposerPlaceholder(root);
            if (placeholder != null) {
                click(placeholder);
                for (int i = 0; i < 15 && comp == null; i++) {
                    sleep(150);
                    root = getRootInActiveWindow();
                    if (root != null) comp = findBottomComposer(root);
                }
            }
        }

        if (comp == null) {
            comp = findEditable(root);
        }
        if (comp == null) return "ERR:composer not found";

        comp.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        comp.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(150);

        boolean set = setText(comp, text);
        if (!set) {
            // Clipboard paste fallback for Compose / Flutter / custom views
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("AgentMsg", text));
                    sleep(100);
                    set = comp.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                }
            } catch (Exception ignored) {}
        }

        if (send) {
            sleep(300);
            String sendRes = uiSmartClick("send");
            if (!"OK".equals(sendRes)) {
                comp.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }
        }
        return set ? "OK" : "ERR:failed to set text";
    }

    /**
     * Universal Smart Button Identifier across ANY Android app.
     * Resolves buttons by semantic action (like, share, subscribe, comment, send, play/pause, next, prev, call, cart, close)
     * or arbitrary button labels with multi-lingual fuzzy recognition and gesture fallback.
     */
    public String uiSmartClick(String query) {
        if (query == null || query.trim().isEmpty()) return "ERR:nothing to click";
        String q = query.toLowerCase(Locale.ROOT).trim();

        for (int attempt = 0; attempt < 15; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                if (isLikeQuery(q)) {
                    if (clickLike(root)) return "OK";
                } else if (isShareQuery(q)) {
                    if (clickShare(root)) return "OK";
                } else if (isSubscribeQuery(q)) {
                    if (clickSubscribe(root)) return "OK";
                } else if (isCommentQuery(q)) {
                    if (clickComment(root)) return "OK";
                } else if (isSendQuery(q)) {
                    if (clickSendButton(root)) return "OK";
                } else if (isPlayPauseQuery(q)) {
                    if (clickPlayPause(root)) return "OK";
                } else if (isNextQuery(q)) {
                    if (clickNext(root)) return "OK";
                } else if (isPrevQuery(q)) {
                    if (clickPrev(root)) return "OK";
                } else if (isCallQuery(q)) {
                    if (clickCall(root, false)) return "OK";
                } else if (isVideoCallQuery(q)) {
                    if (clickCall(root, true)) return "OK";
                } else if (isCartQuery(q)) {
                    if (clickCart(root)) return "OK";
                } else if (isCloseQuery(q)) {
                    if (clickClose(root)) return "OK";
                }

                // General match: view ID, label, contentDescription, or visible text
                AccessibilityNodeInfo target = findSmartTarget(root, q);
                if (target != null && click(target)) {
                    return "OK";
                }
            }
            sleep(150);
        }
        return "ERR:button not found: " + query;
    }

    /**
     * Reads and summarizes everything visible on the active screen:
     * active package, title, buttons, texts, and input fields.
     */
    public String uiReadScreen() {
        AccessibilityNodeInfo root = waitRoot(2000);
        if (root == null) return "{\"error\":\"cannot read screen\"}";

        try {
            JSONObject res = new JSONObject();
            CharSequence pSeq = root.getPackageName();
            res.put("package", pSeq != null ? pSeq.toString() : "");

            JSONArray buttons = new JSONArray();
            JSONArray texts = new JSONArray();
            JSONArray inputs = new JSONArray();

            List<AccessibilityNodeInfo> nodes = all(root);
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null || !n.isVisibleToUser()) continue;

                String txt = ownText(n);
                if (n.isEditable()) {
                    JSONObject inp = new JSONObject();
                    inp.put("text", txt);
                    CharSequence hint = n.getHintText();
                    if (hint != null) inp.put("hint", hint.toString());
                    inputs.put(inp);
                } else if (n.isClickable() || (n.getClassName() != null && n.getClassName().toString().toLowerCase(Locale.ROOT).contains("button"))) {
                    if (!txt.isEmpty() && txt.length() < 70) {
                        buttons.put(txt);
                    }
                } else if (!txt.isEmpty() && txt.length() > 1 && txt.length() < 300) {
                    texts.put(txt);
                }
            }

            res.put("buttons", buttons);
            res.put("texts", texts);
            res.put("inputs", inputs);
            return res.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Intelligently clicks a specific item/card/contact matching text on screen.
     * Automatically scrolls if not immediately in view.
     */
    public String uiOpenItemByText(String query) {
        if (query == null || query.trim().isEmpty()) return "ERR:nothing to open";
        String q = query.toLowerCase(Locale.ROOT).trim();

        for (int attempt = 0; attempt < 2; attempt++) {
            AccessibilityNodeInfo root = waitRoot(2000);
            if (root != null && !isOwnApp(root)) {
                // Check direct matches
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser() || isExcludedHeaderOrNav(n)) continue;
                    String txt = ownText(n);
                    if (!txt.isEmpty() && (txt.equals(q) || txt.contains(q))) {
                        AccessibilityNodeInfo target = clickableSelfOrParent(n);
                        if (target == null) target = n;
                        if (click(target)) return "OK";
                    }
                }
            }
            if (attempt == 0) {
                uiScroll(true);
                sleep(600);
            }
        }
        return "ERR:item not found: " + query;
    }

    /**
     * Universal directional swipe (left, right, up, down) for stories, carousels, cards, tabs, and feeds.
     */
    public String uiSwipeDirection(String dir) {
        if (dir == null || dir.isEmpty()) dir = "up";
        String d = dir.toLowerCase(Locale.ROOT).trim();
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels > 0 ? dm.widthPixels : 1080;
            int screenH = dm.heightPixels > 0 ? dm.heightPixels : 2400;

            float startX, startY, endX, endY;
            if ("left".equals(d)) {
                startX = screenW * 0.85f; endX = screenW * 0.15f;
                startY = screenH * 0.50f; endY = screenH * 0.50f;
            } else if ("right".equals(d)) {
                startX = screenW * 0.15f; endX = screenW * 0.85f;
                startY = screenH * 0.50f; endY = screenH * 0.50f;
            } else if ("down".equals(d)) {
                startX = screenW * 0.50f; endX = screenW * 0.50f;
                startY = screenH * 0.25f; endY = screenH * 0.75f;
            } else {
                startX = screenW * 0.50f; endX = screenW * 0.50f;
                startY = screenH * 0.75f; endY = screenH * 0.25f;
            }

            Path p = new Path();
            p.moveTo(startX, startY);
            p.lineTo(endX, endY);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 250)).build();
            return dispatchGesture(g, null, null) ? "OK" : "ERR:swipe failed";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    /**
     * Selects and clicks any tab on the navigation bar (home, search, reels, notifications, profile, orders, library, settings).
     */
    public String uiClickTab(String tabName) {
        if (tabName == null || tabName.trim().isEmpty()) return "ERR:empty tab name";
        String q = tabName.toLowerCase(Locale.ROOT).trim();

        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int screenH = dm.heightPixels > 0 ? dm.heightPixels : 2400;
                Rect r = new Rect();

                // Search in bottom bar (top > 70% of screen) or top bar (bottom < 35% of screen)
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    n.getBoundsInScreen(r);
                    boolean isNavRegion = r.top > (screenH * 0.70f) || r.bottom < (screenH * 0.35f);
                    if (!isNavRegion) continue;

                    String l = label(n);
                    if (isTabMatch(l, q) && (n.isClickable() || clickableSelfOrParent(n) != null)) {
                        return click(n) ? "OK" : "ERR:cannot click tab";
                    }
                }
            }
            sleep(150);
        }
        return "ERR:tab not found: " + tabName;
    }

    private static boolean isTabMatch(String l, String q) {
        if (q.contains("home") || q.contains("ஹோம்")) {
            return l.contains("home") || l.contains("feed") || l.contains("main") || l.contains("ஹோம்") || l.contains("முகப்பு");
        } else if (q.contains("search") || q.contains("explore") || q.contains("தேடு")) {
            return l.contains("search") || l.contains("explore") || l.contains("discover") || l.contains("தேடு");
        } else if (q.contains("reel") || q.contains("short") || q.contains("video") || q.contains("ரீல்ஸ்")) {
            return l.contains("reel") || l.contains("clips") || l.contains("shorts") || l.contains("video") || l.contains("ரீல்ஸ்");
        } else if (q.contains("notif") || q.contains("activity") || q.contains("அறிவிப்பு")) {
            return l.contains("notification") || l.contains("activity") || l.contains("inbox") || l.contains("updates") || l.contains("அறிவிப்பு");
        } else if (q.contains("profile") || q.contains("account") || q.contains("me") || q.contains("சுயவிவரம்") || q.contains("கணக்கு")) {
            return l.contains("profile") || l.contains("account") || l.contains("me") || l.contains("avatar") || l.contains("சுயவிவரம்") || l.contains("கணக்கு");
        } else if (q.contains("order") || q.contains("cart") || q.contains("ஆர்டர்") || q.contains("கார்ட்")) {
            return l.contains("order") || l.contains("cart") || l.contains("bag") || l.contains("basket") || l.contains("கார்ட்");
        } else if (q.contains("lib") || q.contains("subscript") || q.contains("நூலகம்")) {
            return l.contains("library") || l.contains("subscription") || l.contains("playlists");
        }
        return l.contains(q);
    }

    /**
     * Opens the 3-dots overflow menu or hamburger navigation drawer in any app.
     */
    public String uiClickMenu() {
        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                // 1. Known overflow menu / drawer IDs
                String[] menuIds = {
                    "android:id/action_menu_presenter",
                    "com.android.internal:id/more_options",
                    "id/more_options",
                    "id/menu_button",
                    "id/overflow_button",
                    "id/action_overflow",
                    "id/btn_more",
                    "id/action_more",
                    "id/navigation_drawer",
                    "id/drawer_layout"
                };
                for (String mId : menuIds) {
                    List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(mId);
                    if (hits != null && !hits.isEmpty()) {
                        for (AccessibilityNodeInfo h : hits) {
                            if (h != null && h.isVisibleToUser() && click(h)) return "OK";
                        }
                    }
                }

                // 2. Scan labels matching "more options", "menu", "overflow", "drawer"
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    String l = label(n);
                    if (l.contains("more options") || l.contains("more_options") ||
                        l.contains("open navigation drawer") || l.contains("main menu") ||
                        l.equals("menu") || l.contains("overflow")) {
                        if (click(n)) return "OK";
                    }
                }
            }
            sleep(150);
        }
        return "ERR:menu not found";
    }

    /**
     * Automatically handles popups, permission dialogs, bottom sheets, and confirmation dialogs
     * (allow, deny, confirm, ok, cancel, yes, no, dismiss, save, apply).
     */
    public String uiHandleDialog(String action) {
        if (action == null || action.trim().isEmpty()) action = "ok";
        String a = action.toLowerCase(Locale.ROOT).trim();
        boolean isPositive = a.contains("allow") || a.contains("ok") || a.contains("yes") ||
                             a.contains("confirm") || a.contains("save") || a.contains("apply") ||
                             a.contains("அனுமதி") || a.contains("சரி") || a.contains("ஆம்") ||
                             a.contains("permit") || a.contains("continue");

        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                // Try standard button1 (positive) or button2 (negative)
                List<AccessibilityNodeInfo> btnList = root.findAccessibilityNodeInfosByViewId(
                        isPositive ? "android:id/button1" : "android:id/button2");
                if (btnList != null && !btnList.isEmpty()) {
                    for (AccessibilityNodeInfo b : btnList) {
                        if (b != null && b.isVisibleToUser() && click(b)) return "OK";
                    }
                }

                // Scan dialog buttons
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    String l = label(n);
                    if (isPositive) {
                        if ((l.contains("allow") || l.contains("while using") || l.contains("only this time") ||
                             l.contains("confirm") || l.contains("yes") || l.equals("ok") || l.equals("okay") ||
                             l.contains("அனுமதி") || l.contains("சரி") || l.contains("ஆம்") || l.contains("save")) &&
                            (n.isClickable() || clickableSelfOrParent(n) != null)) {
                            return click(n) ? "OK" : "ERR:cannot click dialog button";
                        }
                    } else {
                        if ((l.contains("deny") || l.contains("don't allow") || l.contains("cancel") ||
                             l.contains("no") || l.contains("dismiss") || l.contains("மறு") ||
                             l.contains("இல்லை") || l.contains("ரத்து") || l.contains("not now") || l.contains("later")) &&
                            (n.isClickable() || clickableSelfOrParent(n) != null)) {
                            return click(n) ? "OK" : "ERR:cannot click dialog button";
                        }
                    }
                }
            }
            sleep(150);
        }
        return "ERR:dialog action failed: " + action;
    }

    /**
     * Toggles a Switch or CheckBox for a setting inside any app.
     */
    public String uiToggleSwitch(String targetName, Boolean turnOn) {
        if (targetName == null || targetName.trim().isEmpty()) return "ERR:target required";
        String q = targetName.toLowerCase(Locale.ROOT).trim();

        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && !isOwnApp(root)) {
                for (AccessibilityNodeInfo n : all(root)) {
                    if (n == null || !n.isVisibleToUser()) continue;
                    String l = label(n);
                    if (l.contains(q)) {
                        // Look for switch/checkbox in self, children, or parent row
                        AccessibilityNodeInfo sw = findCheckableNode(n);
                        if (sw == null && n.getParent() != null) {
                            sw = findCheckableNode(n.getParent());
                        }
                        if (sw != null) {
                            boolean current = sw.isChecked();
                            if (turnOn == null || current != turnOn) {
                                return click(sw) ? "OK" : "ERR:cannot toggle";
                            }
                            return "OK";
                        } else {
                            // If no separate switch child, clicking the row itself often toggles it
                            if (click(n)) return "OK";
                        }
                    }
                }
            }
            sleep(150);
        }
        return "ERR:switch not found for: " + targetName;
    }

    private AccessibilityNodeInfo findCheckableNode(AccessibilityNodeInfo root) {
        if (root == null) return null;
        if (root.isCheckable()) return root;
        for (AccessibilityNodeInfo ch : all(root)) {
            if (ch != null && ch.isCheckable() && ch.isVisibleToUser()) return ch;
        }
        return null;
    }

    /**
     * Master Feature Runner: Executes ANY button, tab, card, or menu feature in the active app.
     * Automatically inspects screen, overflow menu, and scrolls if needed.
     */
    public String uiExecuteFeature(String query) {
        if (query == null || query.trim().isEmpty()) return "ERR:nothing to execute";
        String q = query.toLowerCase(Locale.ROOT).trim();

        // 1. Direct screen click check
        String res = uiSmartClick(q);
        if ("OK".equals(res)) return "OK";

        // 2. Check if feature is in 3-dots overflow menu
        uiClickMenu();
        sleep(350);
        AccessibilityNodeInfo menuRoot = getRootInActiveWindow();
        if (menuRoot != null) {
            for (AccessibilityNodeInfo n : all(menuRoot)) {
                if (n == null || !n.isVisibleToUser()) continue;
                String l = label(n);
                if (l.contains(q) && (n.isClickable() || clickableSelfOrParent(n) != null)) {
                    return click(n) ? "OK" : "ERR:cannot click menu item";
                }
            }
            performGlobalAction(GLOBAL_ACTION_BACK); // dismiss menu if not found
            sleep(250);
        }

        // 3. Scroll down once and re-check
        uiScroll(true);
        sleep(500);
        return uiSmartClick(q);
    }

    // ------------------------------------------------------------------ Smart Button Helpers
    private static boolean isLikeQuery(String q) {
        return q.equals("like") || q.equals("love") || q.equals("favorite") || q.equals("heart") ||
                q.contains("லைக்") || q.contains("விருப்பம்") || q.contains("பிடி") ||
                q.equals("いいね") || q.contains("पसंद");
    }

    private static boolean isShareQuery(String q) {
        return q.equals("share") || q.equals("forward") || q.equals("send to") ||
                q.contains("ஷேர்") || q.contains("பகிர்") || q.equals("共有") || q.contains("शेयर");
    }

    private static boolean isSubscribeQuery(String q) {
        return q.equals("subscribe") || q.equals("sub") || q.equals("follow") || q.equals("following") ||
                q.contains("சப்ஸ்கிரைப்") || q.contains("பின்தொடர்") || q.equals("チャンネル登録") || q.contains("フォロー") || q.contains("सब्सक्राइब");
    }

    private static boolean isCommentQuery(String q) {
        return q.equals("comment") || q.equals("comments") || q.equals("reply") ||
                q.contains("கருத்து") || q.contains("கமெண்ட்") || q.equals("コメント") || q.contains("टिप्पणी");
    }

    private static boolean isSendQuery(String q) {
        return q.equals("send") || q.equals("send it") || q.equals("anuppu") ||
                q.contains("அனுப்பு") || q.contains("அனுப்பி விடு") || q.equals("送信") || q.contains("भेजें");
    }

    private static boolean isPlayPauseQuery(String q) {
        return q.equals("play") || q.equals("pause") || q.equals("play_pause") || q.equals("resume") ||
                q.contains("இயக்கு") || q.contains("நிறுத்து") || q.contains("போடு") || q.equals("再生") || q.equals("一時停止");
    }

    private static boolean isNextQuery(String q) {
        return q.equals("next") || q.equals("skip") || q.equals("forward") ||
                q.contains("அடுத்த") || q.equals("次へ") || q.contains("अगला");
    }

    private static boolean isPrevQuery(String q) {
        return q.equals("prev") || q.equals("previous") || q.equals("rewind") ||
                q.contains("முந்தைய") || q.equals("前へ") || q.contains("पिछला");
    }

    private static boolean isCallQuery(String q) {
        return q.equals("call") || q.equals("voice call") || q.equals("dial") ||
                q.contains("அழை") || q.contains("கால்") || q.equals("通話");
    }

    private static boolean isVideoCallQuery(String q) {
        return q.equals("video call") || q.equals("video_call") ||
                q.contains("வீடியோ கால்") || q.equals("ビデオ通話");
    }

    private static boolean isCartQuery(String q) {
        return q.equals("cart") || q.equals("basket") || q.equals("add to cart") || q.equals("buy now") ||
                q.contains("கார்ட்") || q.contains("கூடை") || q.contains("வாங்க") || q.equals("カート");
    }

    private static boolean isCloseQuery(String q) {
        return q.equals("close") || q.equals("cancel") || q.equals("dismiss") || q.equals("exit") || q.equals("cross") ||
                q.contains("மூடு") || q.contains("ரத்து") || q.equals("閉じる");
    }

    private boolean clickLike(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.instagram.android:id/row_feed_button_like",
            "com.instagram.android:id/like_button",
            "com.instagram.android:id/feed_like_button",
            "com.google.android.youtube:id/like_button",
            "com.google.android.youtube:id/toggle_button",
            "com.spotify.music:id/button_heart",
            "com.spotify.music:id/heart_button",
            "com.twitter.android:id/like",
            "com.facebook.katana:id/like_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("like") || l.contains("favorite") || l.contains("thumbs up") || l.contains("லைக்") || l.contains("விருப்பம்")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickShare(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.instagram.android:id/row_feed_button_share",
            "com.instagram.android:id/direct_share_button",
            "com.google.android.youtube:id/share_button",
            "com.spotify.music:id/button_share",
            "com.twitter.android:id/share"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("share") || l.contains("forward") || l.contains("ஷேர்") || l.contains("பகிர்")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickSubscribe(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.google.android.youtube:id/subscribe_button",
            "com.instagram.android:id/button_follow",
            "com.instagram.android:id/follow_button",
            "com.twitter.android:id/follow_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("subscribe") || l.contains("follow") || l.contains("சப்ஸ்கிரைப்") || l.contains("பின்தொடர்")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickComment(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.instagram.android:id/row_feed_button_comment",
            "com.instagram.android:id/comment_button",
            "com.google.android.youtube:id/comments_entry_point",
            "com.google.android.youtube:id/comment_text",
            "com.twitter.android:id/reply"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("comment") || l.contains("கருத்து") || l.contains("reply")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_container",
            "com.instagram.android:id/row_thread_composer_send_button_container",
            "com.instagram.android:id/row_thread_composer_button_send",
            "com.instagram.android:id/direct_text_send_button",
            "com.google.android.apps.messaging:id/send_message_button_icon",
            "com.google.android.apps.messaging:id/send_message_button",
            "org.telegram.messenger:id/send_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("send") || l.contains("அனுப்பு") || l.contains("送信")) &&
                !l.contains("resend") && !l.contains("cancel") && (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickPlayPause(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.google.android.youtube:id/player_control_play_pause_replay_button",
            "com.google.android.youtube:id/play_pause_button",
            "com.spotify.music:id/play_pause_button",
            "com.spotify.music:id/button_play_pause"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("play") || l.contains("pause") || l.contains("இயக்கு") || l.contains("நிறுத்து")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }

        // Fallback for auto-hiding video players: tap center of video area to wake controls
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int screenW = dm.widthPixels > 0 ? dm.widthPixels : 1080;
            int screenH = dm.heightPixels > 0 ? dm.heightPixels : 2400;
            Path p = new Path();
            p.moveTo(screenW / 2.0f, screenH * 0.35f);
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 50)).build();
            dispatchGesture(g, null, null);
            sleep(250);
            AccessibilityNodeInfo updatedRoot = getRootInActiveWindow();
            if (updatedRoot != null) {
                for (String id : ids) {
                    List<AccessibilityNodeInfo> hits = updatedRoot.findAccessibilityNodeInfosByViewId(id);
                    if (hits != null && !hits.isEmpty()) {
                        for (AccessibilityNodeInfo node : hits) {
                            if (node != null && node.isVisibleToUser() && click(node)) return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean clickNext(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.google.android.youtube:id/next_button",
            "com.spotify.music:id/button_next",
            "com.spotify.music:id/next_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("next") || l.contains("skip") || l.contains("அடுத்த")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickPrev(AccessibilityNodeInfo root) {
        String[] ids = {
            "com.google.android.youtube:id/prev_button",
            "com.spotify.music:id/button_prev",
            "com.spotify.music:id/previous_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("prev") || l.contains("previous") || l.contains("முந்தைய")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickCall(AccessibilityNodeInfo root, boolean isVideo) {
        String[] ids = isVideo ? new String[]{
            "com.whatsapp:id/video_call",
            "com.whatsapp:id/menuitem_video_call",
            "com.instagram.android:id/action_bar_button_video_call"
        } : new String[]{
            "com.whatsapp:id/voice_call",
            "com.whatsapp:id/menuitem_calls",
            "com.instagram.android:id/action_bar_button_call",
            "com.google.android.dialer:id/incall_first_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if (isVideo) {
                if ((l.contains("video call") || l.contains("வீடியோ கால்")) && (n.isClickable() || clickableSelfOrParent(n) != null)) {
                    return click(n);
                }
            } else {
                if ((l.contains("call") || l.contains("voice call") || l.contains("அழைப்பு")) &&
                    !l.contains("video") && (n.isClickable() || clickableSelfOrParent(n) != null)) {
                    return click(n);
                }
            }
        }
        return false;
    }

    private boolean clickCart(AccessibilityNodeInfo root) {
        String[] ids = {
            "in.amazon.mShop.android.shopping:id/chrome_action_bar_cart_count",
            "com.amazon.mShop.android.shopping:id/action_bar_cart",
            "com.flipkart.android:id/cart_icon",
            "in.amazon.mShop.android.shopping:id/add_to_cart_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("cart") || l.contains("add to cart") || l.contains("buy now") || l.contains("கார்ட்") || l.contains("கூடை")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private boolean clickClose(AccessibilityNodeInfo root) {
        String[] ids = {
            "android:id/closeButton",
            "com.instagram.android:id/action_bar_button_back",
            "com.google.android.youtube:id/close_button"
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(id);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo h : hits) {
                    if (h != null && h.isVisibleToUser() && click(h)) return true;
                }
            }
        }
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            String l = label(n);
            if ((l.contains("close") || l.contains("cancel") || l.contains("dismiss") || l.contains("மூடு") || l.contains("ரத்து")) &&
                (n.isClickable() || clickableSelfOrParent(n) != null)) {
                return click(n);
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findSmartTarget(AccessibilityNodeInfo root, String q) {
        // 1. Exact text match
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isVisibleToUser()) continue;
            String t = ownText(n);
            if (!t.isEmpty() && t.equals(q)) {
                AccessibilityNodeInfo c = clickableSelfOrParent(n);
                return c != null ? c : n;
            }
        }
        // 2. View ID entry match
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isVisibleToUser()) continue;
            String vId = n.getViewIdResourceName();
            if (vId != null && vId.contains(":id/")) {
                String entry = vId.substring(vId.indexOf(":id/") + 4).toLowerCase(Locale.ROOT);
                if (entry.equals(q) || entry.contains(q)) {
                    AccessibilityNodeInfo c = clickableSelfOrParent(n);
                    return c != null ? c : n;
                }
            }
        }
        // 3. Contains label match
        for (AccessibilityNodeInfo n : all(root)) {
            if (!n.isVisibleToUser()) continue;
            String l = label(n);
            if (!l.isEmpty() && l.contains(q)) {
                AccessibilityNodeInfo c = clickableSelfOrParent(n);
                return c != null ? c : n;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findBottomComposer(AccessibilityNodeInfo root) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int h = dm.heightPixels > 0 ? dm.heightPixels : 2400;
        int thresholdY = (int) (h * 0.38f);

        // 1. Known composer view IDs
        String[] composerIds = {
            "com.whatsapp:id/entry",
            "com.whatsapp:id/conversation_text_entry",
            "com.whatsapp:id/input_holder",
            "com.instagram.android:id/row_thread_composer_edittext",
            "com.instagram.android:id/comment_text_area",
            "com.instagram.android:id/layout_comment_thread_edittext",
            "com.google.android.apps.messaging:id/compose_message_text",
            "com.google.android.apps.messaging:id/message_text",
            "org.telegram.messenger:id/chat_text_edit",
            "org.telegram.messenger:id/message_text_edit",
            "com.facebook.orca:id/composer_text_tab",
            "com.google.android.youtube:id/comment_text"
        };
        for (String cId : composerIds) {
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(cId);
            if (hits != null && !hits.isEmpty()) {
                for (AccessibilityNodeInfo hit : hits) {
                    if (hit != null && hit.isVisibleToUser()) return hit;
                }
            }
        }

        // 2. Scan all editable nodes located in the lower half of the screen
        Rect r = new Rect();
        AccessibilityNodeInfo bestBottom = null;
        int maxBottom = -1;

        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isEditable() || !n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (r.top >= thresholdY && r.bottom > maxBottom) {
                maxBottom = r.bottom;
                bestBottom = n;
            }
        }
        return bestBottom;
    }

    private AccessibilityNodeInfo findComposerPlaceholder(AccessibilityNodeInfo root) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int h = dm.heightPixels > 0 ? dm.heightPixels : 2400;
        int thresholdY = (int) (h * 0.38f);
        Rect r = new Rect();

        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || !n.isVisibleToUser()) continue;
            n.getBoundsInScreen(r);
            if (r.top < thresholdY) continue;

            String l = label(n);
            if (l.contains("message") || l.contains("reply") || l.contains("comment") ||
                l.contains("type a message") || l.contains("செய்தி") || l.contains("கருத்து")) {
                AccessibilityNodeInfo target = clickableSelfOrParent(n);
                if (target != null) return target;
                return n;
            }
        }
        return null;
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
        if (n.getViewIdResourceName() != null) {
            String vId = n.getViewIdResourceName();
            int idx = vId.indexOf(":id/");
            if (idx != -1) {
                sb.append(vId.substring(idx + 4)).append(' ');
            } else {
                sb.append(vId).append(' ');
            }
        }
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
        if (root == null) return null;
        CharSequence rootPkgSeq = root.getPackageName();
        String rootPkg = rootPkgSeq == null ? "" : rootPkgSeq.toString().toLowerCase(Locale.ROOT);

        // 1. Google App fast path: find the real Google Search widget (NOT the account avatar!)
        if (rootPkg.contains("googlequicksearchbox")) {
            String[] googleSearchIds = {
                "com.google.android.googlequicksearchbox:id/googleapp_search_box",
                "com.google.android.googlequicksearchbox:id/search_box",
                "com.google.android.googlequicksearchbox:id/search_edit_frame",
                "com.google.android.googlequicksearchbox:id/search_plate",
                "com.google.android.googlequicksearchbox:id/open_search_view_edit_text",
                "com.google.android.googlequicksearchbox:id/search_text",
                "com.google.android.googlequicksearchbox:id/search_box_container",
                "com.google.android.googlequicksearchbox:id/search_widget"
            };
            for (String gId : googleSearchIds) {
                List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(gId);
                if (hits != null && !hits.isEmpty()) {
                    for (AccessibilityNodeInfo h : hits) {
                        if (h != null && h.isVisibleToUser() && !isAccountOrSettingsElement(h)) {
                            AccessibilityNodeInfo target = clickableSelfOrParent(h);
                            if (target != null) return target;
                            return h;
                        }
                    }
                }
            }
        }

        // 2. Exact text or contentDescription match for search trigger across all apps (ignoring account elements)
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || n.isEditable() || !n.isVisibleToUser() || isAccountOrSettingsElement(n)) continue;
            String own = ownText(n);
            if (own.equals("search") || own.equals("search...") || own.equals("தேடு") || own.equals("தேடல்") || own.equals("検索")) {
                AccessibilityNodeInfo target = clickableSelfOrParent(n);
                if (target != null) return target;
            }
        }

        // 3. View ID entry match (checking only resource entry name, not package)
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || n.isEditable() || !n.isVisibleToUser() || isAccountOrSettingsElement(n)) continue;
            String vId = n.getViewIdResourceName();
            if (vId != null && vId.contains(":id/")) {
                String idEntry = vId.substring(vId.indexOf(":id/") + 4).toLowerCase(Locale.ROOT);
                if (idEntry.contains("search") && !idEntry.contains("account") && !idEntry.contains("avatar") && !idEntry.contains("profile")) {
                    AccessibilityNodeInfo target = clickableSelfOrParent(n);
                    if (target != null) return target;
                }
            }
        }

        // 4. Broader search words in label, strictly excluding accounts and settings
        for (AccessibilityNodeInfo n : all(root)) {
            if (n == null || n.isEditable() || !n.isVisibleToUser() || isAccountOrSettingsElement(n)) continue;
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
