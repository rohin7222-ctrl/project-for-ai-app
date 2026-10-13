package com.nextgen.agent;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Everything the assistant can do on the phone. execute() returns "OK", "ERR:<reason>" or "NEED_ACCESSIBILITY". */
public class DeviceController {
    private final Context ctx;

    public DeviceController(Context c) { ctx = c.getApplicationContext(); }

    /** Actions that cost money / affect other people: the UI asks the user to confirm first. */
    public static boolean isSensitive(String type) {
        return "call".equals(type) || "sms".equals(type) || "end_call".equals(type);
    }

    public String execute(JSONObject a) {
        String type = a.optString("type");
        try {
            switch (type) {
                case "call": return call(a.optString("target"));
                case "sms": return sms(a.optString("target"), a.optString("text"));
                case "open_app": return openApp(a.optString("name"));
                case "web_search": return view("https://www.google.com/search?q=" + Uri.encode(a.optString("query")));
                case "youtube": return view("https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")));
                case "navigate": return view("geo:0,0?q=" + Uri.encode(a.optString("query")));
                case "open_url": {
                    String u = a.optString("url");
                    return u.startsWith("http") ? view(u) : "ERR:invalid url";
                }
                case "alarm": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                            .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                            .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                            .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    return start(i);
                }
                case "timer": {
                    Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                            .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                            .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                    return start(i);
                }
                case "flashlight": return flashlight(a.optBoolean("on", true));
                case "volume": return volume(a.optString("dir"));
                case "system": return system(a.optString("name"));
                case "settings": return settings(a.optString("page"));
                case "answer_call": return telecom(true);
                case "end_call": return telecom(false);
                case "ui_search": return ui(0, a);
                case "ui_open_nth": return ui(1, a);
                case "ui_click": return ui(2, a);
                case "ui_type": return ui(3, a);
                case "ui_scroll": return ui(4, a);
                default: return "ERR:unknown action " + type;
            }
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    // ---------- helpers ----------
    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private String start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return "OK";
    }

    private String view(String uri) { return start(new Intent(Intent.ACTION_VIEW, Uri.parse(uri))); }

    /** Phone number as-is, or look a contact name up in the address book. */
    private String resolveNumber(String target) {
        if (target == null || target.trim().isEmpty()) return null;
        String digits = target.replaceAll("[\\s\\-()]", "");
        if (digits.matches("\\+?\\d{3,15}")) return digits;
        if (!has(Manifest.permission.READ_CONTACTS)) return null;
        // Speech recognition often adds extra words ("abhi night"): if nothing matches, drop trailing words and retry.
        String name = target.trim();
        while (!name.isEmpty()) {
            String num = lookup(name);
            if (num != null) return num;
            int sp = name.lastIndexOf(' ');
            if (sp < 0) break;
            name = name.substring(0, sp).trim();
        }
        return null;
    }

    private String lookup(String name) {
        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
                new String[]{"%" + name + "%"},
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC LIMIT 1")) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    // ---------- actions ----------
    private String call(String target) {
        if (!has(Manifest.permission.CALL_PHONE)) return "ERR:phone permission not granted";
        String num = resolveNumber(target);
        if (num == null) return "ERR:contact not found: " + target;
        return start(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))));
    }

    private String sms(String target, String text) {
        if (!has(Manifest.permission.SEND_SMS)) return "ERR:sms permission not granted";
        String num = resolveNumber(target);
        if (num == null) return "ERR:contact not found: " + target;
        SmsManager sm = android.os.Build.VERSION.SDK_INT >= 31
                ? ctx.getSystemService(SmsManager.class) : SmsManager.getDefault();
        ArrayList<String> parts = sm.divideMessage(text);
        sm.sendMultipartTextMessage(num, null, parts, null, null);
        return "OK";
    }

    private String openApp(String name) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String q = name.toLowerCase(Locale.ROOT).trim();
        String best = null;
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            if (label.equals(q)) { best = ri.activityInfo.packageName; break; }
            if (best == null && (label.contains(q) || q.contains(label))) best = ri.activityInfo.packageName;
        }
        if (best == null) return "ERR:app not found: " + name;
        Intent i = pm.getLaunchIntentForPackage(best);
        return i == null ? "ERR:cannot launch " + name : start(i);
    }

    private String flashlight(boolean on) throws Exception {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) { cm.setTorchMode(id, on); return "OK"; }
        }
        return "ERR:no flashlight";
    }

    private String volume(String dir) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int d = "down".equals(dir) ? AudioManager.ADJUST_LOWER
                : "mute".equals(dir) ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_RAISE;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI);
        return "OK";
    }

    private String system(String name) {
        int act;
        switch (name) {
            case "home": act = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "back": act = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "recents": act = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": act = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": act = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock": act = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot": act = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default: return "ERR:unknown system action " + name;
        }
        if (AgentAccessibilityService.instance == null) return "NEED_ACCESSIBILITY";
        return AgentAccessibilityService.perform(act) ? "OK" : "ERR:system action failed";
    }

    private String settings(String page) {
        String a;
        switch (page) {
            case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
            case "bluetooth": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": a = Settings.ACTION_SOUND_SETTINGS; break;
            case "battery": a = Settings.ACTION_BATTERY_SAVER_SETTINGS; break;
            case "apps": a = Settings.ACTION_APPLICATION_SETTINGS; break;
            default: a = Settings.ACTION_SETTINGS;
        }
        return start(new Intent(a));
    }

    /** Inside-the-app control through the accessibility service. Blocks, so call from a background thread. */
    private String ui(int kind, JSONObject a) {
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s == null) return "NEED_ACCESSIBILITY";
        switch (kind) {
            case 0: return s.uiSearch(a.optString("query"), a.optBoolean("submit", true));
            case 1: return s.uiOpenNth(a.optInt("n", 1));
            case 2: return s.uiClick(a.optString("text"));
            case 3: return s.uiType(a.optString("text"), a.optBoolean("send", false));
            default: return s.uiScroll(!"up".equals(a.optString("dir")));
        }
    }

    private String telecom(boolean answer) {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return "ERR:call permission not granted";
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (answer) tm.acceptRingingCall(); else tm.endCall();
        return "OK";
    }
}
