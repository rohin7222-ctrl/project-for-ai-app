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
import java.util.List;
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
        RELATION_ALIASES.put("athai", new String[]{"aunt", "aunty", "அத்தை", "மாமி"});
        RELATION_ALIASES.put("aunt", new String[]{"athai", "chithi", "aunty", "அத்தை"});
        RELATION_ALIASES.put("mama", new String[]{"uncle", "மாமா", "மாம்ஸ்"});
        RELATION_ALIASES.put("uncle", new String[]{"mama", "chithappa", "periyappa", "மாமா"});
        RELATION_ALIASES.put("chithappa", new String[]{"uncle", "சித்தப்பா"});
        RELATION_ALIASES.put("periyappa", new String[]{"uncle", "பெரியப்பா"});
        RELATION_ALIASES.put("chithi", new String[]{"aunt", "சித்தி"});
        RELATION_ALIASES.put("thatha", new String[]{"grandpa", "grandfather", "தாத்தா", "பாபா"});
        RELATION_ALIASES.put("grandpa", new String[]{"thatha", "தாத்தா"});
        RELATION_ALIASES.put("paati", new String[]{"grandma", "grandmother", "பாட்டி", "ஆச்சி"});
        RELATION_ALIASES.put("grandma", new String[]{"paati", "பாட்டி"});
        RELATION_ALIASES.put("boss", new String[]{"manager", "sir", "முதலாளி", "பாஸ்"});
        RELATION_ALIASES.put("friend", new String[]{"machi", "buddy", "தோழன்", "நண்பன்", "மச்சி"});
        RELATION_ALIASES.put("doctor", new String[]{"dr", "மருத்துவர்"});
    }

    private static final java.util.Map<String, String[]> KNOWN_PACKAGES = new java.util.HashMap<>();
    static {
        KNOWN_PACKAGES.put("whatsapp", new String[]{"com.whatsapp", "com.whatsapp.w4b"});
        KNOWN_PACKAGES.put("instagram", new String[]{"com.instagram.android"});
        KNOWN_PACKAGES.put("youtube", new String[]{"com.google.android.youtube"});
        KNOWN_PACKAGES.put("chrome", new String[]{"com.android.chrome"});
        KNOWN_PACKAGES.put("gmail", new String[]{"com.google.android.gm"});
        KNOWN_PACKAGES.put("maps", new String[]{"com.google.android.apps.maps"});
        KNOWN_PACKAGES.put("camera", new String[]{"com.google.android.GoogleCamera", "com.sec.android.app.camera", "com.android.camera", "com.android.camera2"});
        KNOWN_PACKAGES.put("gallery", new String[]{"com.google.android.apps.photos", "com.sec.android.gallery3d", "com.android.gallery3d", "com.miui.gallery"});
        KNOWN_PACKAGES.put("photos", new String[]{"com.google.android.apps.photos"});
        KNOWN_PACKAGES.put("spotify", new String[]{"com.spotify.music"});
        KNOWN_PACKAGES.put("telegram", new String[]{"org.telegram.messenger", "org.telegram.messenger.web"});
        KNOWN_PACKAGES.put("twitter", new String[]{"com.twitter.android"});
        KNOWN_PACKAGES.put("x", new String[]{"com.twitter.android"});
        KNOWN_PACKAGES.put("facebook", new String[]{"com.facebook.katana", "com.facebook.lite"});
        KNOWN_PACKAGES.put("snapchat", new String[]{"com.snapchat.android"});
        KNOWN_PACKAGES.put("settings", new String[]{"com.android.settings"});
        KNOWN_PACKAGES.put("play store", new String[]{"com.android.vending"});
        KNOWN_PACKAGES.put("playstore", new String[]{"com.android.vending"});
        KNOWN_PACKAGES.put("calculator", new String[]{"com.google.android.calculator", "com.sec.android.app.popupcalculator", "com.android.calculator2"});
        KNOWN_PACKAGES.put("clock", new String[]{"com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock"});
        KNOWN_PACKAGES.put("files", new String[]{"com.google.android.apps.nbu.files", "com.sec.android.app.myfiles", "com.android.documentsui"});
        KNOWN_PACKAGES.put("netflix", new String[]{"com.netflix.mediaclient"});
        KNOWN_PACKAGES.put("amazon", new String[]{"in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping"});
        KNOWN_PACKAGES.put("flipkart", new String[]{"com.flipkart.android"});
        KNOWN_PACKAGES.put("zomato", new String[]{"com.application.zomato"});
        KNOWN_PACKAGES.put("swiggy", new String[]{"in.swiggy.android"});
        KNOWN_PACKAGES.put("paytm", new String[]{"net.one97.paytm"});
        KNOWN_PACKAGES.put("gpay", new String[]{"com.google.android.apps.nbu.paisa.user"});
        KNOWN_PACKAGES.put("google pay", new String[]{"com.google.android.apps.nbu.paisa.user"});
        KNOWN_PACKAGES.put("phonepe", new String[]{"com.phonepe.app"});
        KNOWN_PACKAGES.put("uber", new String[]{"com.ubercab"});
        KNOWN_PACKAGES.put("ola", new String[]{"com.olacabs.customer"});
        KNOWN_PACKAGES.put("rapido", new String[]{"com.rapido.passenger"});
        KNOWN_PACKAGES.put("jiosaavn", new String[]{"com.jio.media.jiobeats"});
        KNOWN_PACKAGES.put("saavn", new String[]{"com.jio.media.jiobeats"});
        KNOWN_PACKAGES.put("wynk", new String[]{"com.bsb.hns"});
        KNOWN_PACKAGES.put("gaana", new String[]{"com.gaana"});
        KNOWN_PACKAGES.put("youtube music", new String[]{"com.google.android.apps.youtube.music"});
        KNOWN_PACKAGES.put("yt music", new String[]{"com.google.android.apps.youtube.music"});
        KNOWN_PACKAGES.put("apple music", new String[]{"com.apple.android.music"});
        KNOWN_PACKAGES.put("prime video", new String[]{"com.amazon.avod.thirdpartyclient"});
        KNOWN_PACKAGES.put("hotstar", new String[]{"in.startv.hotstar"});
        KNOWN_PACKAGES.put("disney", new String[]{"in.startv.hotstar"});
        KNOWN_PACKAGES.put("sonyliv", new String[]{"com.sonyliv"});
        KNOWN_PACKAGES.put("zee5", new String[]{"com.graymatrix.did"});
        KNOWN_PACKAGES.put("jiocinema", new String[]{"com.jio.media.ondemand"});
        KNOWN_PACKAGES.put("jio cinema", new String[]{"com.jio.media.ondemand"});
        KNOWN_PACKAGES.put("meesho", new String[]{"com.meesho.supply"});
        KNOWN_PACKAGES.put("myntra", new String[]{"com.myntra.android"});
        KNOWN_PACKAGES.put("ajio", new String[]{"com.ril.ajio"});
        KNOWN_PACKAGES.put("zepto", new String[]{"com.zeptoconsumerapp"});
        KNOWN_PACKAGES.put("blinkit", new String[]{"com.grofers.customerapp"});
        KNOWN_PACKAGES.put("bigbasket", new String[]{"com.bigbasket.mobileapp"});
        KNOWN_PACKAGES.put("cred", new String[]{"com.dreamplug.androidapp"});
        KNOWN_PACKAGES.put("linkedin", new String[]{"com.linkedin.android"});
        KNOWN_PACKAGES.put("discord", new String[]{"com.discord"});
        KNOWN_PACKAGES.put("reddit", new String[]{"com.reddit.frontpage"});
        KNOWN_PACKAGES.put("slack", new String[]{"com.Slack"});
        KNOWN_PACKAGES.put("notion", new String[]{"notion.id"});
        KNOWN_PACKAGES.put("teams", new String[]{"com.microsoft.teams"});
        KNOWN_PACKAGES.put("chatgpt", new String[]{"com.openai.chatgpt"});
        KNOWN_PACKAGES.put("claude", new String[]{"com.anthropic.claude"});
        KNOWN_PACKAGES.put("duolingo", new String[]{"com.duolingo"});
        KNOWN_PACKAGES.put("zerodha", new String[]{"com.zerodha.kite3"});
        KNOWN_PACKAGES.put("groww", new String[]{"com.nextbillion.groww"});
        KNOWN_PACKAGES.put("pinterest", new String[]{"com.pinterest"});
        KNOWN_PACKAGES.put("canva", new String[]{"com.canva.editor"});
        KNOWN_PACKAGES.put("bgmi", new String[]{"com.pubg.imobile"});
        KNOWN_PACKAGES.put("bookmyshow", new String[]{"com.bt.bms"});
    }

    /** Actions that cost money / affect other people / delete data: the UI asks the user to confirm first. */
    public static boolean isSensitive(String type) {
        return "sms".equals(type) || "end_call".equals(type);
    }

    public static boolean isSensitive(JSONObject a) {
        if (a == null) return false;
        String type = a.optString("type", "");
        if ("sms".equals(type) || "end_call".equals(type)) return true;

        if ("open_app".equals(type)) {
            String name = a.optString("name", "").toLowerCase(Locale.ROOT);
            if (name.contains("gpay") || name.contains("google pay") || name.contains("phonepe") ||
                name.contains("paytm") || name.contains("cred") || name.contains("bhim") ||
                name.contains("பேடிஎம்") || name.contains("கூகுள் பே") || name.contains("போன்பே")) {
                return true;
            }
        }

        if (type.startsWith("ui_")) {
            String query = a.optString("query", "").toLowerCase(Locale.ROOT);
            String text = a.optString("text", "").toLowerCase(Locale.ROOT);
            String name = a.optString("name", "").toLowerCase(Locale.ROOT);
            if (query.contains("delete") || query.contains("remove") || query.contains("clear") ||
                query.contains("நீக்கு") || query.contains("அழி") || query.contains("uninstall") ||
                text.contains("delete") || text.contains("clear") || text.contains("நீக்கு") ||
                name.contains("delete") || name.contains("uninstall")) {
                return true;
            }
        }
        return false;
    }

    public String execute(JSONObject a) {
        String type = a.optString("type");
        try {
            switch (type) {
                case "call": return call(a.optString("target"));
                case "sms": return sms(a.optString("target"), a.optString("text"));
                case "open_app": return openApp(a.optString("name"));
                case "chat": return openChat(a.optString("app"), a.optString("target"), a.optString("text"));
                case "music_play": return playMusic(a.optString("query"), a.optString("app"));
                case "google_search":
                case "web_search": return webSearch(a.optString("query"));
                case "youtube": return openYouTube(a.optString("query"), false);
                case "youtube_play": return openYouTube(a.optString("query"), true);
                case "instagram": return openApp("instagram");
                case "instagram_reels": return openInstagramReels();
                case "instagram_dm": return openInstagramDirect(a.optString("target"));
                case "navigate": return view("geo:0,0?q=" + Uri.encode(a.optString("query")));
                case "open_url": return openUrl(a.optString("url"));
                case "hotspot": return hotspot(a.optBoolean("on", true));
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
                case "mobile_data": return mobileData(a.optBoolean("on", true));
                case "brightness": return brightness(a.optString("dir", "up"), a.optInt("value", -1));
                case "rotation": return rotation(a.optBoolean("on", true));
                case "dnd": return dnd(a.optBoolean("on", true));
                case "location": return locationToggle();
                case "take_photo": return takePhoto();
                case "read_notifications": return readNotifications();
                case "read_sms": return readSms();
                case "calendar_event": return calendarEvent(a.optString("title"), a.optString("time"));
                case "volume": return volume(a.optString("dir"));
                case "media": return media(a.optString("cmd"));
                case "clipboard": return clipboard(a.optString("text"));
                case "system": return system(a.optString("name"));
                case "settings": return settings(a.optString("page"));
                case "stop_assistant": return "OK";
                case "answer_call": return telecom(true);
                case "end_call": return telecom(false);
                case "ui_search":
                case "search": return ui(0, a);
                case "ui_open_nth":
                case "open_nth":
                case "select_nth": return ui(1, a);
                case "ui_click":
                case "click":
                case "tap": return ui(2, a);
                case "ui_type":
                case "type": return ui(3, a);
                case "ui_scroll":
                case "scroll": return ui(4, a);
                case "ui_clear_text": return ui(5, a);
                case "ui_open_last_file": return ui(6, a);
                case "ui_smart_click":
                case "smart_click": return ui(7, a);
                case "ui_swipe_reels": return ui(8, a);
                case "ui_type_composer": return ui(9, a);
                case "ui_read_screen": return ui(10, a);
                case "ui_open_item": return ui(11, a);
                case "ui_swipe":
                case "swipe": return ui(12, a);
                case "ui_click_tab":
                case "click_tab": return ui(13, a);
                case "ui_menu": return ui(14, a);
                case "ui_dialog":
                case "ui_handle_dialog": return ui(15, a);
                case "ui_dismiss_popup":
                case "dismiss_popup": return (AgentAccessibilityService.instance != null && AgentAccessibilityService.instance.uiDismissPopup()) ? "OK" : "NO_POPUP";
                case "ui_toggle": return ui(16, a);
                case "ui_feature": return ui(17, a);
                case "battery_status": return batteryStatus();
                case "uninstall_app": return uninstallApp(a.optString("name"));
                case "ui_skip_ad": return ui(18, a);
                case "ui_reels_cruise": return ui(19, a);
                case "ui_fill_form": return ui(20, a);
                case "ui_read_otp": return ui(21, a);
                case "ui_grid_tap": return ui(22, a);
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

    public String openApp(String name) {
        if (name == null || name.trim().isEmpty()) return "ERR:empty app name";
        PackageManager pm = ctx.getPackageManager();
        String q = name.toLowerCase(Locale.ROOT).trim();
        q = q.replaceAll("^(open|launch|start|run|the)\\s+", "");
        q = q.replaceAll("\\s+(app|application|செயலி|ஆப்)$", "");

        // 1. Check known packages
        String[] pkgs = KNOWN_PACKAGES.get(q);
        if (pkgs != null) {
            for (String pkg : pkgs) {
                Intent i = pm.getLaunchIntentForPackage(pkg);
                if (i != null) return start(i);
            }
        }

        // Special system handlers
        if ("camera".equals(q)) {
            try { return start(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)); } catch (Exception ignored) {}
        } else if ("phone".equals(q) || "dialer".equals(q)) {
            try { return start(new Intent(Intent.ACTION_DIAL)); } catch (Exception ignored) {}
        } else if ("messages".equals(q) || "sms".equals(q)) {
            try {
                Intent smsApp = pm.getLaunchIntentForPackage(android.provider.Telephony.Sms.getDefaultSmsPackage(ctx));
                if (smsApp != null) return start(smsApp);
            } catch (Exception ignored) {}
        } else if ("settings".equals(q)) {
            try { return start(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }

        // 2. Search all launcher activities
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        String best = null;
        int bestScore = -1;

        for (ResolveInfo ri : list) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT).trim();

            if (label.equals(q) || pkg.toLowerCase(Locale.ROOT).endsWith("." + q)) {
                best = pkg;
                bestScore = 1000;
                break;
            }
            if (label.startsWith(q)) {
                int sc = 800 - (label.length() - q.length());
                if (sc > bestScore) { best = pkg; bestScore = sc; }
            } else if (label.contains(q) && q.length() >= 3) {
                int sc = 600 - (label.length() - q.length());
                if (sc > bestScore) { best = pkg; bestScore = sc; }
            } else {
                int dist = levenshtein(q, label);
                if (dist <= 2 && label.length() >= 4) {
                    int sc = 400 - dist * 50;
                    if (sc > bestScore) { best = pkg; bestScore = sc; }
                }
            }
        }

        if (best != null) {
            Intent i = pm.getLaunchIntentForPackage(best);
            if (i != null) return start(i);
        }

        // Fallback: If not an installed app, try to click the matching item/button on the active screen via Accessibility
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s != null) {
            String smartRes = s.uiSmartClick(name);
            if ("OK".equals(smartRes)) return "OK";
            String clickRes = s.uiClick(name);
            if ("OK".equals(clickRes)) return "OK";
        }

        return "ERR:app or item not found: " + name;
    }

    public String openChat(String app, String target) {
        return openChat(app, target, null);
    }

    public String openChat(String app, String target, String text) {
        String a = (app == null) ? "whatsapp" : app.toLowerCase(Locale.ROOT).trim();
        if (a.contains("insta")) {
            return openInstagramDirect(target);
        }
        if (a.contains("telegram")) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + Uri.encode(target)));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "OK";
            } catch (Exception e) {
                return openApp("telegram");
            }
        }
        // WhatsApp chat
        String num = resolveNumber(target);
        if (num != null) {
            String digits = num.replaceAll("[^0-9]", "");
            String url = "https://api.whatsapp.com/send?phone=" + digits;
            if (text != null && !text.trim().isEmpty()) {
                url += "&text=" + Uri.encode(text.trim());
            }
            try {
                Intent wa = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                wa.setPackage("com.whatsapp");
                wa.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(wa);
                if (text != null && !text.trim().isEmpty()) {
                    new Thread(() -> {
                        try { Thread.sleep(1500); } catch (Exception ignored) {}
                        AgentAccessibilityService s = AgentAccessibilityService.instance;
                        if (s != null) {
                            s.uiSmartClick("send");
                        }
                    }).start();
                }
                return "OK";
            } catch (Exception ignored) {
                return view(url);
            }
        } else {
            // Open WhatsApp and search contact
            String res = openApp("whatsapp");
            if ("OK".equals(res) && target != null && !target.isEmpty()) {
                new Thread(() -> {
                    try { Thread.sleep(1200); } catch (Exception ignored) {}
                    AgentAccessibilityService s = AgentAccessibilityService.instance;
                    if (s != null) {
                        s.uiSearch(target, true);
                        if (text != null && !text.trim().isEmpty()) {
                            try { Thread.sleep(1500); } catch (Exception ignored) {}
                            s.uiOpenNth(1);
                            try { Thread.sleep(1500); } catch (Exception ignored) {}
                            s.uiTypeComposer(text, true);
                        }
                    }
                }).start();
            }
            return res;
        }
    }

    public String openInstagramDirect(String target) {
        try {
            if (target != null && !target.trim().isEmpty() && !target.equalsIgnoreCase("chat") && !target.equalsIgnoreCase("direct")) {
                String cleanUser = target.trim().replace("@", "");
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/_u/" + Uri.encode(cleanUser)));
                i.setPackage("com.instagram.android");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "OK";
            } else {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/direct/inbox/"));
                i.setPackage("com.instagram.android");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "OK";
            }
        } catch (Exception ignored) {
            return openApp("instagram");
        }
    }

    public String openInstagramReels() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://instagram.com/reels/"));
            i.setPackage("com.instagram.android");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "OK";
        } catch (Exception ignored) {
            return openApp("instagram");
        }
    }

    public String openYouTube(String query, boolean autoPlayFirst) {
        String q = (query == null) ? "" : query.trim();
        try {
            Intent yt = new Intent(Intent.ACTION_SEARCH);
            yt.setPackage("com.google.android.youtube");
            yt.putExtra("query", q);
            yt.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(yt);
        } catch (Exception ignored) {
            view("https://www.youtube.com/results?search_query=" + Uri.encode(q));
        }

        if (autoPlayFirst) {
            // Background worker to auto-click top video in search results
            new Thread(() -> {
                try { Thread.sleep(1500); } catch (Exception ignored) {}
                AgentAccessibilityService s = AgentAccessibilityService.instance;
                if (s != null) {
                    s.uiOpenNth(1);
                }
            }).start();
        }
        return "OK";
    }

    public String hotspot(boolean on) {
        try {
            Intent i = new Intent();
            i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);

            new Thread(() -> {
                try { Thread.sleep(600); } catch (Exception ignored) {}
                AgentAccessibilityService s = AgentAccessibilityService.instance;
                if (s != null) {
                    s.uiClick("Hotspot");
                    s.uiClick("Wi‑Fi hotspot");
                    s.uiClick("ஹாட்ஸ்பாட்");
                }
            }).start();
            return "OK";
        } catch (Exception e) {
            try {
                Intent tether = new Intent("android.settings.WIFI_TETHER_SETTINGS");
                tether.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(tether);
                return "OK";
            } catch (Exception ex) {
                return start(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
            }
        }
    }

    public String openUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "ERR:empty url";
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return view(u);
    }

    public String webSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            return openUrl("https://www.google.com");
        }
        try {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
            i.putExtra(android.app.SearchManager.QUERY, query);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return "OK";
        } catch (Exception ignored) {
            return view("https://www.google.com/search?q=" + Uri.encode(query));
        }
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
                new Thread(() -> {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                    AgentAccessibilityService s = AgentAccessibilityService.instance;
                    if (s != null) {
                        s.uiToggleAnySwitch(on);
                    }
                }).start();
                return "OK";
            } else {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    wm.setWifiEnabled(on);
                    return "OK";
                }
            }
        } catch (Exception ignored) {}
        start(new Intent(Settings.ACTION_WIFI_SETTINGS));
        new Thread(() -> {
            try { Thread.sleep(700); } catch (Exception ignored) {}
            AgentAccessibilityService s = AgentAccessibilityService.instance;
            if (s != null) {
                s.uiToggleAnySwitch(on);
            }
        }).start();
        return "OK";
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
                            new Thread(() -> {
                                try { Thread.sleep(500); } catch (Exception ignored) {}
                                AgentAccessibilityService s = AgentAccessibilityService.instance;
                                if (s != null) {
                                    s.uiClick("Allow");
                                    s.uiClick("அனுமதி");
                                }
                            }).start();
                            return "OK";
                        }
                        return "OK";
                    } else if (Build.VERSION.SDK_INT < 33) {
                        adapter.enable();
                        return "OK";
                    }
                } else {
                    if (Build.VERSION.SDK_INT < 33) {
                        adapter.disable();
                        return "OK";
                    }
                }
            }
        } catch (Exception ignored) {}
        start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        new Thread(() -> {
            try { Thread.sleep(700); } catch (Exception ignored) {}
            AgentAccessibilityService s = AgentAccessibilityService.instance;
            if (s != null) {
                s.uiToggleAnySwitch(on);
            }
        }).start();
        return "OK";
    }

    private String mobileData(boolean on) {
        try {
            Intent intent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            new Thread(() -> {
                try { Thread.sleep(700); } catch (Exception ignored) {}
                AgentAccessibilityService s = AgentAccessibilityService.instance;
                if (s != null) {
                    s.uiToggleSwitch("Mobile data", on);
                    s.uiToggleSwitch("Mobile Data", on);
                    s.uiToggleSwitch("மொபைல் டேட்டா", on);
                    s.uiToggleAnySwitch(on);
                }
            }).start();
            return "OK";
        } catch (Exception ignored) {
            return start(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    private String brightness(String dir, int val) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(ctx)) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                    i.setData(Uri.parse("package:" + ctx.getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return "ERR:NEED_WRITE_SETTINGS";
                }
            }
            android.content.ContentResolver cr = ctx.getContentResolver();
            int current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            int target = val >= 0 ? val : ("down".equals(dir) ? Math.max(10, current - 60) : Math.min(255, current + 60));
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target);
            return "OK";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    private String rotation(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(ctx)) {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.setData(Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return "ERR:NEED_WRITE_SETTINGS";
            }
            Settings.System.putInt(ctx.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 1 : 0);
            return "OK";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    private String dnd(boolean on) {
        try {
            android.app.NotificationManager nm = (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (!nm.isNotificationPolicyAccessGranted()) {
                        Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                        return "ERR:NEED_NOTIFICATION_POLICY";
                    }
                    nm.setInterruptionFilter(on ? android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                            : android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                    return "OK";
                }
            }
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
        return "ERR:cannot set dnd";
    }

    private String locationToggle() {
        try {
            Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            new Thread(() -> {
                try { Thread.sleep(700); } catch (Exception ignored) {}
                AgentAccessibilityService s = AgentAccessibilityService.instance;
                if (s != null) {
                    s.uiToggleAnySwitch(null);
                }
            }).start();
            return "OK";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    private String takePhoto() {
        try {
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            new Thread(() -> {
                try { Thread.sleep(1200); } catch (Exception ignored) {}
                AgentAccessibilityService s = AgentAccessibilityService.instance;
                if (s != null) {
                    s.uiClick("Shutter");
                    s.uiClick("Take photo");
                    s.uiClick("Capture");
                }
            }).start();
            return "OK";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    private String readNotifications() {
        if (!AgentNotificationListenerService.Companion.isListenerEnabled(ctx)) {
            AgentNotificationListenerService.Companion.openSettings(ctx);
            return "ERR:NEED_NOTIFICATION_LISTENER";
        }
        List<NotificationItem> list = AgentNotificationListenerService.Companion.getRecentNotifications();
        if (list.isEmpty()) {
            return "SPEECH:புதிய செய்திகள் எதுவும் இல்லை. (No recent notifications)";
        }
        StringBuilder sb = new StringBuilder();
        int count = Math.min(3, list.size());
        for (int i = 0; i < count; i++) {
            NotificationItem item = list.get(i);
            sb.append(item.getAppName()).append(" வழியே ").append(item.getTitle());
            if (!item.getText().isEmpty()) {
                sb.append(": ").append(item.getText());
            }
            if (i < count - 1) sb.append(". அடுத்த செய்தி: ");
        }
        return "SPEECH:" + sb.toString();
    }

    private String readSms() {
        if (!has(Manifest.permission.READ_SMS)) {
            return "ERR:NEED_PERMISSION_READ_SMS";
        }
        try (Cursor cursor = ctx.getContentResolver().query(
                Uri.parse("content://sms/inbox"),
                new String[]{"address", "body", "date"},
                null, null, "date DESC LIMIT 3")) {
            if (cursor != null && cursor.moveToFirst()) {
                StringBuilder sb = new StringBuilder();
                int idx = 0;
                do {
                    String address = cursor.getString(0);
                    String body = cursor.getString(1);
                    String sender = smartLookup(address);
                    if (sender == null) sender = address;
                    if (idx > 0) sb.append(". அடுத்த எஸ்எம்எஸ்: ");
                    sb.append(sender).append(" அனுப்பியுள்ளார்: ").append(body);
                    idx++;
                } while (cursor.moveToNext());
                return "SPEECH:" + sb.toString();
            }
        } catch (Exception ignored) {}
        return "SPEECH:புதிய SMS எதுவும் வரவில்லை.";
    }

    private String calendarEvent(String title, String time) {
        try {
            Intent intent = new Intent(Intent.ACTION_INSERT)
                    .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                    .putExtra(android.provider.CalendarContract.Events.TITLE, title != null && !title.isEmpty() ? title : "Reminder")
                    .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, "Created by Aero Fenix Assistant")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return "OK";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
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
            case 6: return s.uiOpenLastFile();
            case 7: return s.uiSmartClick(a.optString("action", a.optString("query", a.optString("text"))));
            case 8: return s.uiSwipeReels(a.optBoolean("next", true));
            case 9: return s.uiTypeComposer(a.optString("text"), a.optBoolean("send", false));
            case 10: return s.uiReadScreen();
            case 11: return s.uiOpenItemByText(a.optString("query", a.optString("text", a.optString("target"))));
            case 12: return s.uiSwipeDirection(a.optString("dir", "up"));
            case 13: return s.uiClickTab(a.optString("tab", a.optString("name", a.optString("query"))));
            case 14: return s.uiClickMenu();
            case 15: return s.uiHandleDialog(a.optString("action", "ok"));
            case 16: return s.uiToggleSwitch(a.optString("target", a.optString("name", a.optString("query"))), a.has("on") ? a.optBoolean("on") : null);
            case 17: return s.uiExecuteFeature(a.optString("feature", a.optString("name", a.optString("query"))));
            case 18: return s.uiSkipAd();
            case 19: return s.uiReelsCruise(a.optBoolean("on", true));
            case 20: return s.uiFillForm();
            case 21: return s.uiReadOtp();
            case 22: return s.uiGridTap(a.optString("pos", "center"));
            default: return "ERR:unknown ui action " + kind;
        }
    }

    public String batteryStatus() {
        try {
            Intent batteryIntent = ctx.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                int status = batteryIntent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                if (level >= 0 && scale > 0) {
                    int pct = (int) ((level / (float) scale) * 100);
                    return "Battery is at " + pct + "%" + (isCharging ? " (Charging)" : "");
                }
            }
        } catch (Exception ignored) {}
        return "OK";
    }

    public String resolvePackageForName(String name) {
        if (name == null || name.trim().isEmpty()) return null;
        String q = name.toLowerCase(Locale.ROOT).trim();
        q = q.replaceAll("^(open|launch|start|run|the|uninstall|delete)\\s+", "");
        q = q.replaceAll("\\s+(app|application|செயலி|ஆப்)$", "").trim();

        String[] pkgs = KNOWN_PACKAGES.get(q);
        if (pkgs != null && pkgs.length > 0) return pkgs[0];

        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
        String best = null;
        int bestScore = -1;

        for (ResolveInfo ri : list) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            String label = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT).trim();

            if (label.equals(q) || pkg.toLowerCase(Locale.ROOT).endsWith("." + q)) {
                return pkg;
            }
            if (label.startsWith(q)) {
                int sc = 800 - (label.length() - q.length());
                if (sc > bestScore) { best = pkg; bestScore = sc; }
            } else if (label.contains(q) && q.length() >= 3) {
                int sc = 600 - (label.length() - q.length());
                if (sc > bestScore) { best = pkg; bestScore = sc; }
            } else {
                int dist = levenshtein(q, label);
                if (dist <= 2 && label.length() >= 4) {
                    int sc = 400 - dist * 50;
                    if (sc > bestScore) { best = pkg; bestScore = sc; }
                }
            }
        }
        return best;
    }

    public String uninstallApp(String name) {
        if (name == null || name.trim().isEmpty()) return "ERR:empty app name";
        String pkg = resolvePackageForName(name);
        if (pkg == null) return "ERR:app not found: " + name;

        AgentAccessibilityService.armAutoConfirmUninstall(pkg);

        try {
            Intent i = new Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + pkg));
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            return start(i);
        } catch (Exception e) {
            try {
                Intent i2 = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
                return start(i2);
            } catch (Exception e2) {
                return "ERR:" + e2.getMessage();
            }
        }
    }

    public String playMusic(String query, String app) {
        String q = (query == null) ? "" : query.trim();
        String a = (app == null) ? "spotify" : app.toLowerCase(Locale.ROOT).trim();

        if (a.contains("spotify")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (Exception ignored) {}
                    AgentAccessibilityService s = AgentAccessibilityService.instance;
                    if (s != null) {
                        s.uiOpenNth(1);
                    }
                }).start();
                return "OK";
            } catch (Exception ignored) {
                return openYouTube(q, true);
            }
        }

        if (a.contains("youtube") || a.contains("yt")) {
            try {
                Intent ytMusic = new Intent(Intent.ACTION_SEARCH);
                ytMusic.setPackage("com.google.android.apps.youtube.music");
                ytMusic.putExtra("query", q);
                ytMusic.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(ytMusic);
                new Thread(() -> {
                    try { Thread.sleep(2000); } catch (Exception ignored) {}
                    AgentAccessibilityService s = AgentAccessibilityService.instance;
                    if (s != null) {
                        s.uiOpenNth(1);
                    }
                }).start();
                return "OK";
            } catch (Exception ignored) {
                return openYouTube(q, true);
            }
        }

        String pkg = null;
        if (a.contains("wynk")) pkg = "com.bsb.hns";
        else if (a.contains("jiosaavn") || a.contains("saavn")) pkg = "com.jio.media.jiobeats";
        else if (a.contains("gaana")) pkg = "com.gaana";

        if (pkg != null) {
            String res = openApp(pkg);
            if ("OK".equals(res) && !q.isEmpty()) {
                new Thread(() -> {
                    try { Thread.sleep(2200); } catch (Exception ignored) {}
                    AgentAccessibilityService s = AgentAccessibilityService.instance;
                    if (s != null) {
                        s.uiSearch(q, true);
                        try { Thread.sleep(1800); } catch (Exception ignored) {}
                        s.uiOpenNth(1);
                    }
                }).start();
            }
            return res;
        }

        return openYouTube(q, true);
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

    public String telecom(boolean answer) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (has(Manifest.permission.ANSWER_PHONE_CALLS)) {
                try {
                    TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
                    if (tm != null) {
                        if (answer) tm.acceptRingingCall(); else tm.endCall();
                        return "OK";
                    }
                } catch (Exception ignored) {}
            }
        }
        AgentAccessibilityService s = AgentAccessibilityService.instance;
        if (s != null) {
            if (answer) {
                if ("OK".equals(s.uiClick("Answer")) || "OK".equals(s.uiClick("Accept")) ||
                    "OK".equals(s.uiClick("ஏற்கவும்")) || "OK".equals(s.uiClick("பதில்"))) {
                    return "OK";
                }
            } else {
                if ("OK".equals(s.uiClick("Decline")) || "OK".equals(s.uiClick("Reject")) ||
                    "OK".equals(s.uiClick("End call")) || "OK".equals(s.uiClick("நிராகரி"))) {
                    return "OK";
                }
            }
        }
        return "ERR:call action failed";
    }

    public String answerCall() {
        return telecom(true);
    }

    public String endCall() {
        return telecom(false);
    }
}
