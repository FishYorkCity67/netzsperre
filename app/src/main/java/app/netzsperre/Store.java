package app.netzsperre;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Gespeicherte Einstellungen plus der aktuelle Zustand des Tunnels. */
final class Store {

    interface Listener {
        void onStoreChanged();
    }

    private static Store instance;

    /** true, solange der VPN-Tunnel steht und die gesperrten Apps abfaengt. */
    static volatile boolean tunnelUp;
    /** Letzter Fehler beim Starten (oder null). */
    static volatile String lastError;

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private final SharedPreferences prefs;

    private Store(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("netzsperre", Context.MODE_PRIVATE);
    }

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c);
        return instance;
    }

    Set<String> blocked() {
        return new HashSet<>(prefs.getStringSet("blocked", Collections.emptySet()));
    }

    boolean isBlocked(String pkg) {
        return prefs.getStringSet("blocked", Collections.emptySet()).contains(pkg);
    }

    void setBlocked(String pkg, boolean value) {
        Set<String> s = blocked();
        if (value) s.add(pkg); else s.remove(pkg);
        prefs.edit().putStringSet("blocked", s).apply();
        changed();
    }

    void clearBlocked() {
        prefs.edit().putStringSet("blocked", new HashSet<>()).apply();
        changed();
    }

    boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    void setEnabled(boolean value) {
        prefs.edit().putBoolean("enabled", value).apply();
        changed();
    }

    boolean showSystem() {
        return prefs.getBoolean("showSystem", false);
    }

    void setShowSystem(boolean value) {
        prefs.edit().putBoolean("showSystem", value).apply();
    }

    static void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onStoreChanged();
        });
    }
}
