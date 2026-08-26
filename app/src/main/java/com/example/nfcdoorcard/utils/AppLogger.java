package com.example.nfcdoorcard.utils;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class AppLogger {
    private static final LinkedList<String> logList = new LinkedList<>();
    private static final int MAX_LOGS = 150;
    private static OnLogUpdateListener listener;

    public interface OnLogUpdateListener {
        void onLogUpdate(String allLogs);
    }

    public static void i(String tag, String msg) {
        addLog("I", tag, msg);
        Log.i(tag, msg);
    }

    public static void w(String tag, String msg) {
        addLog("W", tag, msg);
        Log.w(tag, msg);
    }

    public static void e(String tag, String msg) {
        addLog("E", tag, msg);
        Log.e(tag, msg);
    }

    private static synchronized void addLog(String level, String tag, String msg) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = String.format("[%s] %s/%s: %s", time, level, tag, msg);
        logList.addFirst(entry);
        if (logList.size() > MAX_LOGS) logList.removeLast();
        if (listener != null) {
            StringBuilder sb = new StringBuilder();
            for (String s : logList) {
                sb.append(s).append("\n");
            }
            listener.onLogUpdate(sb.toString());
        }
    }

    public static void setListener(OnLogUpdateListener l) {
        listener = l;
        if (l != null) {
            StringBuilder sb = new StringBuilder();
            synchronized (logList) {
                for (String s : logList) sb.append(s).append("\n");
            }
            l.onLogUpdate(sb.toString());
        }
    }

    public static void clear() {
        synchronized (logList) {
            logList.clear();
        }
        if (listener != null) listener.onLogUpdate("");
    }
}
