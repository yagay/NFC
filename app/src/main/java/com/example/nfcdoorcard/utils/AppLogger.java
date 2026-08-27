package com.example.nfcdoorcard.utils;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

public class AppLogger {
    private static final LinkedList<String> logList = new LinkedList<>();
    private static final int MAX_LOGS = 150;
    private static volatile OnLogUpdateListener listener;

    public interface OnLogUpdateListener {
        void onLogUpdate(String allLogs);
    }

    public static void i(String tag, String msg) { addLog("I", tag, msg); Log.i(tag, msg); }
    public static void w(String tag, String msg) { addLog("W", tag, msg); Log.w(tag, msg); }
    public static void e(String tag, String msg) { addLog("E", tag, msg); Log.e(tag, msg); }

    private static void addLog(String level, String tag, String msg) {
        OnLogUpdateListener target;
        String snapshot;
        synchronized (logList) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            logList.addFirst(String.format("[%s] %s/%s: %s", time, level, tag, msg));
            if (logList.size() > MAX_LOGS) logList.removeLast();
            target = listener;
            snapshot = target == null ? null : snapshotLocked();
        }
        if (target != null) target.onLogUpdate(snapshot);
    }

    public static void setListener(OnLogUpdateListener l) {
        listener = l;
        if (l != null) {
            String snapshot;
            synchronized (logList) { snapshot = snapshotLocked(); }
            l.onLogUpdate(snapshot);
        }
    }

    public static void clear() {
        synchronized (logList) { logList.clear(); }
        OnLogUpdateListener target = listener;
        if (target != null) target.onLogUpdate("");
    }

    private static String snapshotLocked() {
        StringBuilder sb = new StringBuilder();
        for (String s : logList) sb.append(s).append('\n');
        return sb.toString();
    }
}
