"""Unit tests for the Aero Fenix local command parser in brain.py.
Tests 60+ command sentences in English, Tanglish, and Tamil script.
All phone commands run locally without requiring any external API call.
"""
import sys
import os
import unittest
import json

# Ensure brain.py can be imported
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import brain


class TestBrainParser(unittest.TestCase):

    def setUp(self):
        self.cfg_ta = json.dumps({"lang": "ta-IN", "name": "Jarvis", "stop_word": "Stop"})
        self.cfg_en = json.dumps({"lang": "en-US", "name": "Jarvis", "stop_word": "Stop"})
        self.cfg_ja = json.dumps({"lang": "ja-JP", "name": "Jarvis", "stop_word": "Stop"})

    def _chat(self, text, lang="en"):
        cfg = self.cfg_ta if lang == "ta" else (self.cfg_ja if lang == "ja" else self.cfg_en)
        res = json.loads(brain.chat(cfg, text))
        self.assertTrue(res.get("ok"), f"Command '{text}' failed: {res}")
        return res

    # 1. Four specific required commands in Task 1
    def test_required_task1_commands(self):
        # 1. "whatsapp la amma thedu"
        r1 = self._chat("whatsapp la amma thedu", "ta")
        self.assertEqual(len(r1["actions"]), 2)
        self.assertEqual(r1["actions"][0]["type"], "open_app")
        self.assertEqual(r1["actions"][0]["name"], "WhatsApp")
        self.assertEqual(r1["actions"][1]["type"], "ui_search")
        self.assertEqual(r1["actions"][1]["query"], "amma")

        # 2. "whatsapp la first chat open pannu"
        r2 = self._chat("whatsapp la first chat open pannu", "ta")
        self.assertEqual(len(r2["actions"]), 2)
        self.assertEqual(r2["actions"][0]["type"], "open_app")
        self.assertEqual(r2["actions"][0]["name"], "WhatsApp")
        self.assertEqual(r2["actions"][1]["type"], "ui_open_nth")
        self.assertEqual(r2["actions"][1]["n"], 1)

        # 3. "instagram la ravi thedu"
        r3 = self._chat("instagram la ravi thedu", "ta")
        self.assertEqual(len(r3["actions"]), 2)
        self.assertEqual(r3["actions"][0]["type"], "open_app")
        self.assertEqual(r3["actions"][0]["name"], "Instagram")
        self.assertEqual(r3["actions"][1]["type"], "ui_search")
        self.assertEqual(r3["actions"][1]["query"], "ravi")

        # 4. "open whatsapp, search amma, open first chat"
        r4 = self._chat("open whatsapp, search amma, open first chat", "en")
        self.assertEqual(len(r4["actions"]), 3)
        self.assertEqual(r4["actions"][0]["type"], "open_app")
        self.assertEqual(r4["actions"][0]["name"], "WhatsApp")
        self.assertEqual(r4["actions"][1]["type"], "ui_search")
        self.assertEqual(r4["actions"][1]["query"], "amma")
        self.assertEqual(r4["actions"][2]["type"], "ui_open_nth")
        self.assertEqual(r4["actions"][2]["n"], 1)

    # 2. System Navigation (6 commands)
    def test_system_navigation(self):
        # 5
        r = self._chat("go home", "en")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "home")
        # 6
        r = self._chat("ஹோம் போ", "ta")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "home")
        # 7
        r = self._chat("go back", "en")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "back")
        # 8
        r = self._chat("பின்னே போ", "ta")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "back")
        # 9
        r = self._chat("recent apps", "en")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "recents")
        # 10
        r = self._chat("ரெசென்ட்ஸ்", "ta")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "recents")

    # 3. Screenshot & Lock (4 commands)
    def test_screenshot_and_lock(self):
        # 11
        r = self._chat("take screenshot", "en")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "screenshot")
        # 12
        r = self._chat("ஸ்கிரீன்ஷாட் எடு", "ta")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "screenshot")
        # 13
        r = self._chat("lock phone", "en")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "lock")
        # 14
        r = self._chat("போன் லாக் பண்ணு", "ta")
        self.assertEqual(r["actions"][0]["type"], "system")
        self.assertEqual(r["actions"][0]["name"], "lock")

    # 4. Hardware Toggles - Wi-Fi, BT, Data, Torch, Hotspot (10 commands)
    def test_hardware_toggles(self):
        # 15
        r = self._chat("wifi on", "en")
        self.assertEqual(r["actions"][0]["type"], "wifi")
        self.assertTrue(r["actions"][0]["on"])
        # 16
        r = self._chat("வைஃபை ஆஃப் பண்ணு", "ta")
        self.assertEqual(r["actions"][0]["type"], "wifi")
        self.assertFalse(r["actions"][0]["on"])
        # 17
        r = self._chat("bluetooth on", "en")
        self.assertEqual(r["actions"][0]["type"], "bluetooth")
        self.assertTrue(r["actions"][0]["on"])
        # 18
        r = self._chat("ப்ளூடூத் ஆப்", "ta")
        self.assertEqual(r["actions"][0]["type"], "bluetooth")
        self.assertFalse(r["actions"][0]["on"])
        # 19
        r = self._chat("mobile data on", "en")
        self.assertEqual(r["actions"][0]["type"], "mobile_data")
        self.assertTrue(r["actions"][0]["on"])
        # 20
        r = self._chat("டேட்டா ஆஃப் பண்ணு", "ta")
        self.assertEqual(r["actions"][0]["type"], "mobile_data")
        self.assertFalse(r["actions"][0]["on"])
        # 21
        r = self._chat("torch on", "en")
        self.assertEqual(r["actions"][0]["type"], "flashlight")
        self.assertTrue(r["actions"][0]["on"])
        # 22
        r = self._chat("டார்ச் அணை", "ta")
        self.assertEqual(r["actions"][0]["type"], "flashlight")
        self.assertFalse(r["actions"][0]["on"])
        # 23
        r = self._chat("hotspot on", "en")
        self.assertEqual(r["actions"][0]["type"], "hotspot")
        self.assertTrue(r["actions"][0]["on"])
        # 24
        r = self._chat("ஹாட்ஸ்பாட் ஆப்", "ta")
        self.assertEqual(r["actions"][0]["type"], "hotspot")
        self.assertFalse(r["actions"][0]["on"])

    # 5. Volume & Brightness Control (8 commands)
    def test_volume_and_brightness(self):
        # 25
        r = self._chat("volume up", "en")
        self.assertEqual(r["actions"][0]["type"], "volume")
        self.assertEqual(r["actions"][0]["dir"], "up")
        # 26
        r = self._chat("வால்யூம் குறை", "ta")
        self.assertEqual(r["actions"][0]["type"], "volume")
        self.assertEqual(r["actions"][0]["dir"], "down")
        # 27
        r = self._chat("mute volume", "en")
        self.assertEqual(r["actions"][0]["type"], "volume")
        self.assertEqual(r["actions"][0]["dir"], "mute")
        # 28
        r = self._chat("volume 80%", "en")
        self.assertEqual(r["actions"][0]["type"], "volume")
        self.assertEqual(r["actions"][0]["value"], 80)
        # 29
        r = self._chat("brightness up", "en")
        self.assertEqual(r["actions"][0]["type"], "brightness")
        self.assertEqual(r["actions"][0]["dir"], "up")
        # 30
        r = self._chat("வெளிச்சம் குறை", "ta")
        self.assertEqual(r["actions"][0]["type"], "brightness")
        self.assertEqual(r["actions"][0]["dir"], "down")
        # 31
        r = self._chat("brightness 50%", "en")
        self.assertEqual(r["actions"][0]["type"], "brightness")
        self.assertEqual(r["actions"][0]["value"], int((50 / 100.0) * 255))
        # 32
        r = self._chat("பிரகாசம் 100%", "ta")
        self.assertEqual(r["actions"][0]["type"], "brightness")
        self.assertEqual(r["actions"][0]["value"], 255)

    # 6. Notifications, SMS, Camera (6 commands)
    def test_notifications_sms_camera(self):
        # 33
        r = self._chat("read notifications", "en")
        self.assertEqual(r["actions"][0]["type"], "read_notifications")
        # 34
        r = self._chat("வந்த செய்தியை படி", "ta")
        self.assertEqual(r["actions"][0]["type"], "read_notifications")
        # 35
        r = self._chat("read sms", "en")
        self.assertEqual(r["actions"][0]["type"], "read_sms")
        # 36
        r = self._chat("எஸ்எம்எஸ் படி", "ta")
        self.assertEqual(r["actions"][0]["type"], "read_sms")
        # 37
        r = self._chat("take photo", "en")
        self.assertEqual(r["actions"][0]["type"], "take_photo")
        # 38
        r = self._chat("போட்டோ எடு", "ta")
        self.assertEqual(r["actions"][0]["type"], "take_photo")

    # 7. Direct App Launch (6 commands)
    def test_app_launch(self):
        # 39
        r = self._chat("open YouTube", "en")
        self.assertEqual(r["actions"][0]["type"], "open_app")
        self.assertEqual(r["actions"][0]["name"], "YouTube")
        # 40
        r = self._chat("வாட்ஸ்அப் திற", "ta")
        self.assertEqual(r["actions"][0]["type"], "open_app")
        self.assertEqual(r["actions"][0]["name"], "WhatsApp")
        # 41
        r = self._chat("open Instagram", "en")
        self.assertEqual(r["actions"][0]["type"], "open_app")
        self.assertEqual(r["actions"][0]["name"], "Instagram")
        # 42
        r = self._chat("கேமரா திற", "ta")
        self.assertEqual(r["actions"][0]["type"], "open_app")
        self.assertEqual(r["actions"][0]["name"], "Camera")
        # 43
        r = self._chat("open settings", "en")
        self.assertEqual(r["actions"][0]["type"], "open_app")
        self.assertEqual(r["actions"][0]["name"], "Settings")
        # 44
        r = self._chat("செட்டிங்ஸ் திற", "ta")
        self.assertEqual(r["actions"][0]["type"], "open_app")
        self.assertEqual(r["actions"][0]["name"], "Settings")

    # 8. Calls & Missed Calls (6 commands)
    def test_calls_and_missed_calls(self):
        # 45
        r = self._chat("call dad", "en")
        self.assertEqual(r["actions"][0]["type"], "call")
        self.assertEqual(r["actions"][0]["target"], "dad")
        # 46
        r = self._chat("அப்பாவுக்கு கால் பண்ணு", "ta")
        self.assertEqual(r["actions"][0]["type"], "call")
        self.assertEqual(r["actions"][0]["target"], "அப்பா")
        # 47
        r = self._chat("missed call to mom", "en")
        self.assertEqual(r["actions"][0]["type"], "missed_call")
        self.assertEqual(r["actions"][0]["target"], "mom")
        self.assertEqual(r["actions"][0]["rings"], 3)
        # 48
        r = self._chat("அம்மாவுக்கு மிஸ்ட் கால் போடு", "ta")
        self.assertEqual(r["actions"][0]["type"], "missed_call")
        self.assertEqual(r["actions"][0]["target"], "அம்மா")
        # 49
        r = self._chat("answer call", "en")
        self.assertEqual(r["actions"][0]["type"], "answer_call")
        # 50
        r = self._chat("கால் கட் பண்ணு", "ta")
        self.assertEqual(r["actions"][0]["type"], "end_call")

    # 9. Reels, Gestures & Swipes (6 commands)
    def test_reels_and_swipes(self):
        # 51
        r = self._chat("next reel", "en")
        self.assertEqual(r["actions"][0]["type"], "ui_swipe_reels")
        self.assertTrue(r["actions"][0]["next"])
        # 52
        r = self._chat("முந்தைய ரீல்ஸ்", "ta")
        self.assertEqual(r["actions"][0]["type"], "ui_swipe_reels")
        self.assertFalse(r["actions"][0]["next"])
        # 53
        r = self._chat("swipe left", "en")
        self.assertEqual(r["actions"][0]["type"], "ui_swipe")
        self.assertEqual(r["actions"][0]["dir"], "left")
        # 54
        r = self._chat("வலதுபுறம் ஸ்வைப்", "ta")
        self.assertEqual(r["actions"][0]["type"], "ui_swipe")
        self.assertEqual(r["actions"][0]["dir"], "right")
        # 55
        r = self._chat("skip ad", "en")
        self.assertEqual(r["actions"][0]["type"], "ui_skip_ad")
        # 56
        r = self._chat("reels cruise on", "en")
        self.assertEqual(r["actions"][0]["type"], "ui_reels_cruise")
        self.assertTrue(r["actions"][0]["on"])

    # 10. Trackpad & Mouse (4 commands)
    def test_mouse_and_trackpad(self):
        # 57
        r = self._chat("mouse click", "en")
        self.assertEqual(r["actions"][0]["type"], "mouse_click")
        # 58
        r = self._chat("மவுஸ் கிளிக்", "ta")
        self.assertEqual(r["actions"][0]["type"], "mouse_click")
        # 59
        r = self._chat("toggle mouse", "en")
        self.assertEqual(r["actions"][0]["type"], "mouse_toggle")
        # 60
        r = self._chat("டிராக்க்பேட்", "ta")
        self.assertEqual(r["actions"][0]["type"], "mouse_toggle")

    # 11. Stop Commands & Edge Cases (4 commands)
    def test_stop_and_edge_cases(self):
        # 61
        r = self._chat("stop", "en")
        self.assertEqual(r["actions"][0]["type"], "stop_assistant")
        # 62
        r = self._chat("போதும் நிறுத்து", "ta")
        self.assertEqual(r["actions"][0]["type"], "stop_assistant")
        # 63
        r = self._chat("open status", "en")
        self.assertEqual(r["actions"][0]["type"], "ui_smart_click")
        # 64
        r = self._chat("open last file", "en")
        self.assertEqual(r["actions"][0]["type"], "ui_open_last_file")

    # 12. Top Wheel & Down Wheel Commands (User explicit request)
    def test_wheel_commands(self):
        # 65: "actice top wheel" (typo in prompt supported)
        r = self._chat("actice top wheel", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_top")

        # 66: "active top wheel"
        r = self._chat("active top wheel", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_top")

        # 67: "top wheel open pannu"
        r = self._chat("top wheel open pannu", "ta")
        self.assertEqual(r["actions"][0]["type"], "wheel_top")

        # 68: "active down wheel"
        r = self._chat("active down wheel", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_bottom")

        # 69: "actice down wheel"
        r = self._chat("actice down wheel", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_bottom")

        # 70: "down wheel open pannu"
        r = self._chat("down wheel open pannu", "ta")
        self.assertEqual(r["actions"][0]["type"], "wheel_bottom")

        # 71: "actice top wheel to"
        r = self._chat("actice top wheel to", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_top")

        # 72: "top top wheel open aganum"
        r = self._chat("top top wheel open aganum", "ta")
        self.assertEqual(r["actions"][0]["type"], "wheel_top")

        # 73: "downwheel open aganum"
        r = self._chat("downwheel open aganum", "ta")
        self.assertEqual(r["actions"][0]["type"], "wheel_bottom")

        # 74: "close top wheel"
        r = self._chat("close top wheel", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_top_close")

        # 75: "close down wheel"
        r = self._chat("close down wheel", "en")
        self.assertEqual(r["actions"][0]["type"], "wheel_bottom_close")


if __name__ == "__main__":
    unittest.main()

