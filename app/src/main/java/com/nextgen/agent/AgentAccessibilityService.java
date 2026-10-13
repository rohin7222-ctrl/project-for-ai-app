package com.nextgen.agent;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Gives the assistant system-level buttons (Home, Back, Recents, notifications, lock, screenshot). */
public class AgentAccessibilityService extends AccessibilityService {
    public static AgentAccessibilityService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public boolean onUnbind(Intent intent) { instance = null; return super.onUnbind(intent); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    public static boolean perform(int globalAction) {
        return instance != null && instance.performGlobalAction(globalAction);
    }
}