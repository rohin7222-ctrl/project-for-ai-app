"""Unit tests for the Aero Fenix local command parser in brain.py.
Tests Tamil, English, and Japanese commands, multi-step actions, and edge cases.
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

    def test_system_navigation(self):
        res = json.loads(brain.chat(self.cfg_en, "go home"))
        self.assertTrue(res["ok"])
        self.assertEqual(res["actions"][0]["type"], "system")
        self.assertEqual(res["actions"][0]["name"], "home")

        res_ta = json.loads(brain.chat(self.cfg_ta, "ஹோம் போ"))
        self.assertTrue(res_ta["ok"])
        self.assertEqual(res_ta["actions"][0]["type"], "system")
        self.assertEqual(res_ta["actions"][0]["name"], "home")

    def test_hardware_controls(self):
        # Wi-Fi On/Off
        res_wifi = json.loads(brain.chat(self.cfg_en, "wifi on"))
        self.assertEqual(res_wifi["actions"][0]["type"], "wifi")
        self.assertTrue(res_wifi["actions"][0]["on"])

        res_wifi_ta = json.loads(brain.chat(self.cfg_ta, "வைஃபை ஆஃப் பண்ணு"))
        self.assertEqual(res_wifi_ta["actions"][0]["type"], "wifi")
        self.assertFalse(res_wifi_ta["actions"][0]["on"])

        # Bluetooth
        res_bt = json.loads(brain.chat(self.cfg_en, "bluetooth off"))
        self.assertEqual(res_bt["actions"][0]["type"], "bluetooth")
        self.assertFalse(res_bt["actions"][0]["on"])

        # Mobile Data
        res_data = json.loads(brain.chat(self.cfg_ta, "மொபைல் டேட்டா ஆன்"))
        self.assertEqual(res_data["actions"][0]["type"], "mobile_data")
        self.assertTrue(res_data["actions"][0]["on"])

        # Flashlight / Torch
        res_torch = json.loads(brain.chat(self.cfg_ta, "டார்ச் ஆன் பண்ணு"))
        self.assertEqual(res_torch["actions"][0]["type"], "flashlight")
        self.assertTrue(res_torch["actions"][0]["on"])

    def test_display_and_notifications(self):
        # Brightness
        res_bright = json.loads(brain.chat(self.cfg_ta, "பிரகாசம் கூட்டு"))
        self.assertEqual(res_bright["actions"][0]["type"], "brightness")
        self.assertEqual(res_bright["actions"][0]["dir"], "up")

        # Read notifications
        res_notif = json.loads(brain.chat(self.cfg_ta, "வந்த செய்தியை படி"))
        self.assertEqual(res_notif["actions"][0]["type"], "read_notifications")

        # Read SMS
        res_sms = json.loads(brain.chat(self.cfg_ta, "எஸ்எம்எஸ் படி"))
        self.assertEqual(res_sms["actions"][0]["type"], "read_sms")

        # Camera photo
        res_cam = json.loads(brain.chat(self.cfg_ta, "போட்டோ எடு"))
        self.assertEqual(res_cam["actions"][0]["type"], "take_photo")

    def test_app_launch_and_search(self):
        res_app = json.loads(brain.chat(self.cfg_en, "open WhatsApp"))
        self.assertEqual(res_app["actions"][0]["type"], "open_app")
        self.assertEqual(res_app["actions"][0]["name"], "WhatsApp")

        res_app_ta = json.loads(brain.chat(self.cfg_ta, "வாட்ஸ்அப் திற"))
        self.assertEqual(res_app_ta["actions"][0]["type"], "open_app")
        self.assertEqual(res_app_ta["actions"][0]["name"], "WhatsApp")

    def test_quick_local_answers(self):
        # Greetings
        res_hi = json.loads(brain.chat(self.cfg_ta, "வணக்கம்"))
        self.assertTrue(res_hi["ok"])
        self.assertIn("வணக்கம்", res_hi["speech"])

        # Math
        res_math = json.loads(brain.chat(self.cfg_en, "calculate 25 * 4"))
        self.assertTrue(res_math["ok"])
        self.assertIn("100", res_math["speech"])

    def test_in_app_status_and_ui_targets(self):
        # "open my status" must be an on-screen ui_smart_click, NOT open_app or Play Store search
        res_status = json.loads(brain.chat(self.cfg_en, "open my status"))
        self.assertTrue(res_status["ok"])
        self.assertEqual(res_status["actions"][0]["type"], "ui_smart_click")
        self.assertIn("status", res_status["actions"][0]["query"].lower())

        # Tamil: "மை ஸ்டேட்டஸ் திற" or "ஸ்டேட்டஸ் திற"
        res_status_ta = json.loads(brain.chat(self.cfg_ta, "மை ஸ்டேட்டஸ் திற"))
        self.assertTrue(res_status_ta["ok"])
        self.assertEqual(res_status_ta["actions"][0]["type"], "ui_smart_click")

        # Tab navigation: "status page ku po"
        res_tab = json.loads(brain.chat(self.cfg_ta, "ஸ்டேட்டஸ் page ku po"))
        self.assertTrue(res_tab["ok"])
        self.assertEqual(res_tab["actions"][0]["type"], "ui_click_tab")

        # User's exact flow: "open whatsapp and swipe left then open my status"
        res_flow = json.loads(brain.chat(self.cfg_en, "open whatsapp and swipe left then open my status"))
        self.assertTrue(res_flow["ok"])
        self.assertEqual(len(res_flow["actions"]), 3)
        self.assertEqual(res_flow["actions"][0]["type"], "open_app")
        self.assertEqual(res_flow["actions"][0]["name"], "WhatsApp")
        self.assertEqual(res_flow["actions"][1]["type"], "ui_swipe")
        self.assertEqual(res_flow["actions"][1]["dir"], "left")
        self.assertEqual(res_flow["actions"][2]["type"], "ui_smart_click")
        self.assertEqual(res_flow["actions"][2]["query"], "My status")

    def test_stop_command(self):
        res_stop = json.loads(brain.chat(self.cfg_ta, "போதும் நிறுத்து"))
        self.assertEqual(res_stop["actions"][0]["type"], "stop_assistant")

if __name__ == "__main__":
    unittest.main()
