package com.nextgen.agent;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothAdapter;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SmsManager;
import android.view.KeyEvent;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

/** Everything the assistant can do on the phone. execute() returns "OK", "ERR:<reason>" or "NEED_ACCESSIBILITY". */
public class DeviceController {
    private final Context ctx;

    public DeviceController(Context c) { ctx = c.getApplicationContext(); }

    private static final java.util.Map<String, String[]> RELATION_ALIASES = new java.util.HashMap<>();
    static {
        RELATION_ALIASES.put("appa", new String[]{"dad", "daddy", "father", "papa", "dada", "அப்பா", "தந்தை"});
        RELATION_ALIASES.put("dad", new String[]{"appa", "daddy", "father", "papa", "அப்பா"});
        RELATION_ALIASES.put("daddy", new String[]{"appa", "dad", "father", "papa", "அப்பா"});
        RELATION_ALIASES.put("father", new String[]{"appa", "dad", "daddy", "அப்பா"});
        RELATION_ALIASES.put("amma", new String[]{"mom", "mommy", "mother", "mummy", "அம்மா", "தாய்"});
        RELATION_ALIASES.put("mom", new String[]{"amma", "mommy", "mother", "mummy", "அம்மா"});
        RELATION_ALIASES.put("mother", new String[]{"amma", "mom", "mommy", "அம்மா"});
        RELATION_ALIASES.put("anna", new String[]{"brother", "bro", "அண்ணா", "அண்ணன்"});
        RELATION_ALIASES.put("brother", new String[]{"anna", "thambi", "bro"});
        RELATION_ALIASES.put("thambi", new String[]{"brother", "bro", "தம்பி"});
        RELATION_ALIASES.put("akka", new String[]{"sister", "sis", "அக்கா", "அக்காள்"});
        RELATION_ALIASES.put("sister", new String[]{"akka", "thangai", "sis"});
        RELATION_ALIASES.put("thangai", new String[]{"sister", "sis", "தங்கை"});
        RELATION_ALIASES.put("wife", new String[]{"manaivi", "மனைவி", "பொண்டாட்டி"});
        RELATION_ALIASES.put("husband", new String[]{"purushan", "கணவர்"});
        RELATION_ALIASES.put("home", new String[]{"veedu", "வீடு"});
    }

    /** Actions that cost money / affect other people: the UI asks the user to confirm first. */
    public static boolean isSensitive(String type) {
        return "sms".equals(type) || "end_call".equals(type);
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
                case "wifi": return wifi(a.optBoolean("on", true));
                case "bluetooth": return bluetooth(a.optBoolean("on", true));
                case "volume": return volume(a.optString("dir"));
                case "media": return media(a.optString("cmd"));
                case "clipboard": return clipboard(a.optString("text"));
                case "system": return system(a.optString("name"));
                case "settings": return settings(a.optString("page"));
                case "stop_assistant": return "OK";
                case "answer_call": return telecom(true);
                case "end_call": return telecom(false);
                case "ui_search": return ui(0, a);
                case "ui_open_nth": return ui(1, a);
                case "ui_click": return ui(2, a);
                case "ui_type": return ui(3, a);
                case "ui_scroll": return ui(4, a);
                case "ui_clear_text": return ui(5, a);
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

    /** Phone number as-is, or look a contact name up in the address book with smart phonetic/fuzzy matching. */
    private String resolveNumber(String target) {
        if (target == null || target.trim().isEmpty()) return null;
        String digits = target.replaceAll("[\\s\\-()]", "");
        if (digits.matches("\\+?\\d{3,15}")) return digits;
        if (!has(Manifest.permission.READ_CONTACTS)) return null;

        String name = target.trim();
        // 1. Spelled-out letter handling: e.g. "a b i k n i g h t" -> "abiknight"
        String spelledOut = name.replaceAll("(?<=\\b[a-zA-Z])\\s+(?=[a-zA-Z]\\b)", "").trim();

        // 2. Try exact/smart lookup with target
        String num = smartLookup(name);
        if (num != null) return num;

        // 3. Try relationship aliases (e.g. "appa" -> dad, daddy, father, அப்பா)
        String lowerTarget = name.toLowerCase(Locale.ROOT).trim();
        String[] aliases = RELATION_ALIASES.get(lowerTarget);
        if (aliases != null) {
            for (String alias : aliases) {
                num = smartLookup(alias);
                if (num != null) return num;
            }
        }

        // 4. Try smart lookup with spelled-out concatenated version
        if (!spelledOut.equalsIgnoreCase(name) && spelledOut.length() >= 2) {
            num = smartLookup(spelledOut);
            if (num != null) return num;
        }

        // 5. Speech recognition often adds extra words ("abhi night"): if nothing matches, drop trailing words and retry
        while (name.contains(" ")) {
            int sp = name.lastIndexOf(' ');
            name = name.substring(0, sp).trim();
            if (name.length() >= 3) {
                num = smartLookup(name);
                if (num != null) return num;
            }
        }
        return null;
    }

    private static String phonetic(String s) {
        if (s == null) return "";
        String p = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        p = p.replaceAll("kn", "n"); // knight -> night
        p = p.replaceAll("gn", "n"); // knignt -> nignt
        p = p.replaceAll("ph", "f");
        p = p.replaceAll("bh", "b");
        p = p.replaceAll("dh", "d");
        p = p.replaceAll("th", "t");
        p = p.replaceAll("gh", "g");
        p = p.replaceAll("kh", "k");
        p = p.replaceAll("sh", "s");
        p = p.replaceAll("zh", "l");
        p = p.replaceAll("ee", "i");
        p = p.replaceAll("oo", "u");
        p = p.replaceAll("ck", "k");
        return p;
    }

    private static int levenshtein(String a, String b) {
        if (a == null || b == null) return 999;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private String smartLookup(String query) {
        if (query == null || query.trim().length() < 2) return null;
        String qNorm = query.toLowerCase(Locale.ROOT).trim();
        String qClean = qNorm.replaceAll("[^a-z0-9]", "");
        String qPhonetic = phonetic(qNorm);

        String bestNumber = null;
        int bestScore = -1;

        try (Cursor c = ctx.getContentResolver().query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC")) {

            if (c != null) {
                while (c.moveToNext()) {
                    String number = c.getString(0);
                    String displayName = c.getString(1);
                    if (displayName == null || displayName.trim().isEmpty() || number == null) continue;

                    String dNorm = displayName.toLowerCase(Locale.ROOT).trim();
                    String dClean = dNorm.replaceAll("[^a-z0-9]", "");
                    String dPhonetic = phonetic(dNorm);

                    int score = 0;

                    // Exact match
                    if (dNorm.equals(qNorm)) {
                        score = 1000;
                    } else if (dClean.equals(qClean)) {
                        score = 950;
                    } else if (!qPhonetic.isEmpty() && dPhonetic.equals(qPhonetic)) {
                        // Phonetic exact match (e.g. "abhi night" vs "abi knight" or "abi knignt")
                        score = 900;
                    } else if (dNorm.startsWith(qNorm)) {
                        score = 800;
                    } else if (dNorm.contains(qNorm) && qNorm.length() >= 3) {
                        score = 700;
                    } else if (!qPhonetic.isEmpty() && dPhonetic.contains(qPhonetic) && qPhonetic.length() >= 3) {
                        score = 650;
                    } else {
                        // Fuzzy similarity
                        int dist = levenshtein(qClean, dClean);
                        int maxLen = Math.max(qClean.length(), dClean.length());
                        if (maxLen > 0) {
                            double sim = 1.0 - ((double) dist / maxLen);
                            if (sim >= 0.70) {
                                score = (int) (sim * 600);
                            }
                        }

                        // Also check phonetic distance
                        int pDist = levenshtein(qPhonetic, dPhonetic);
                        int pMaxLen = Math.max(qPhonetic.length(), dPhonetic.length());
                        if (pMaxLen > 0) {
                            double pSim = 1.0 - ((double) pDist / pMaxLen);
                            if (pSim >= 0.75) {
                                int pScore = (int) (pSim * 620);
                                if (pScore > score) score = pScore;
                            }
                        }
                    }

                    if (score > bestScore && score >= 400) {
                        bestScore = score;
                        bestNumber = number;
                    }
                }
            }
        } catch (Exception ignored) {}

        return bestNumber;
    }

    // ---------- actions ----------
    private String call(String target) {
        String num = resolveNumber(target);
        if (num == null) {
            // Open phone dialer with target name or number pre-filled so user can tap Call
            try {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(target)));
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(dialIntent);
                return "OK";
            } catch (Exception e) {
                return "ERR:contact not found: " + target;
            }
        }
        if (has(Manifest.permission.CALL_PHONE)) {
            try {
                return start(new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))));
            } catch (Exception e) {
                return start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))));
            }
        } else {
            return start(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num))));
        }
    }

    private String sms(String target, String text) {
        String num = resolveNumber(target);
        if (num == null) num = target;
        if (has(Manifest.permission.SEND_SMS)) {
            try {
                SmsManager sm = Build.VERSION.SDK_INT >= 31
                        ? ctx.getSystemService(SmsManager.class) : SmsManager.getDefault();
                ArrayList<String> parts = sm.divideMessage(text);
                sm.sendMultipartTextMessage(num, null, parts, null, null);
                return "OK";
            } catch (Exception ignored) {}
        }
        // Fallback: Open SMS app directly with number and body prefilled (no restricted permission needed!)
        try {
            Intent smsIntent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num)));
            smsIntent.putExtra("sms_body", text);
            smsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(smsIntent);
            return "OK";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
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

    private String wifi(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // Floating Wi-Fi panel for Android 10+
                Intent panel = new Intent(Settings.Panel.ACTION_WIFI);
                panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(panel);
                return "OK";
            } else {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wm.setWifiEnabled(on);
                    return "OK";
                }
            }
        } catch (Exception ignored) {}
        return start(new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    private String bluetooth(boolean on) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                if (on) {
                    if (Build.VERSION.SDK_INT >= 31 && has(Manifest.permission.BLUETOOTH_CONNECT)) {
                        if (!adapter.isEnabled()) {
                            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                            enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(enableBtIntent);
                            return "OK";
                        }
                        return "OK";
                    } else {
                        adapter.enable();
                        return "OK";
                    }
                } else {
                    adapter.disable();
                    return "OK";
                }
            }
        } catch (Exception ignored) {}
        return start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private String volume(String dir) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int d = "down".equals(dir) ? AudioManager.ADJUST_LOWER
                : "mute".equals(dir) ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_RAISE;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI);
        return "OK";
    }

    private String system(String name) {
        if ("home".equals(name)) {
            // Home works directly without accessibility
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(home);
            return "OK";
        }
        int act;
        switch (name) {
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
            case "hotspot": a = Settings.ACTION_WIRELESS_SETTINGS; break;
            case "data": a = Settings.ACTION_DATA_ROAMING_SETTINGS; break;
            case "airplane": a = Settings.ACTION_AIRPLANE_MODE_SETTINGS; break;
            default: a = Settings.ACTION_SETTINGS;
        }
        return start(new Intent(a));
    }

    /** Inside-the-app control through the accessibility service. Blocks, so call from a background thread. */
    private String ui(int kind, JSONObject a) {
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s == null) {
            AgentAccessibilityService.autoEnable(ctx);
            for (int i = 0; i < 5 && AgentAccessibilityService.instance == null; i++) {
                SystemClock.sleep(200);
            }
            s = AgentAccessibilityService.instance;
        }
        if (s == null) return "NEED_ACCESSIBILITY";
        switch (kind) {
            case 0: return s.uiSearch(a.optString("query"), a.optBoolean("submit", true));
            case 1: return s.uiOpenNth(a.optInt("n", 1));
            case 2: return s.uiClick(a.optString("text"));
            case 3: return s.uiType(a.optString("text"), a.optBoolean("send", false));
            case 4: return s.uiScroll(!"up".equals(a.optString("dir")));
            case 5: return s.uiClearText();
            default: return "ERR:unknown ui action " + kind;
        }
    }

    private String media(String cmd) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "ERR:audio service unavailable";
        int code;
        if ("next".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if ("prev".equals(cmd) || "previous".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if ("stop".equals(cmd)) code = KeyEvent.KEYCODE_MEDIA_STOP;
        else code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        long eventTime = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, code, 0));
        return "OK";
    }

    private String clipboard(String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return "ERR:clipboard unavailable";
        ClipData clip = ClipData.newPlainText("AI Copied Text", text);
        cm.setPrimaryClip(clip);
        return "OK";
    }

    private String telecom(boolean answer) {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return "ERR:call permission not granted";
        TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
        if (answer) tm.acceptRingingCall(); else tm.endCall();
        return "OK";
    }
}
