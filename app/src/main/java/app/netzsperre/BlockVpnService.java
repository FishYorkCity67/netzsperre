package app.netzsperre;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.Set;

/**
 * Lokales VPN, das nur die gesperrten Apps umfasst. Deren gesamter Verkehr (IPv4 und IPv6)
 * landet im Tunnel und wird dort nie weitergeleitet. Alle anderen Apps laufen ganz normal
 * am VPN vorbei. Es verlassen keine Daten das Geraet.
 */
public class BlockVpnService extends VpnService {

    static final String ACTION_APPLY = "app.netzsperre.APPLY";
    static final String ACTION_STOP = "app.netzsperre.STOP";

    private ParcelFileDescriptor tun;

    /** Startet, aktualisiert oder beendet den Tunnel passend zu den gespeicherten Einstellungen. */
    static void apply(Context c) {
        Store s = Store.get(c);
        boolean want = s.enabled() && !s.blocked().isEmpty();
        if (!want && !Store.tunnelUp) {
            Store.lastError = null;
            Store.changed();
            return;
        }
        Intent i = new Intent(c, BlockVpnService.class).setAction(want ? ACTION_APPLY : ACTION_STOP);
        try {
            c.startService(i);
        } catch (RuntimeException e) {
            Store.lastError = c.getString(R.string.err_start);
            Store.changed();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        // ACTION_APPLY, Neustart nach Absturz (intent == null) oder "Durchgehend aktiv" vom System.
        establish();
        return START_STICKY;
    }

    private void establish() {
        Store s = Store.get(this);
        Set<String> blocked = s.blocked();
        if (!s.enabled() || blocked.isEmpty()) {
            shutdown();
            return;
        }

        Builder b = new Builder();
        b.setSession(getString(R.string.app_name));
        b.addAddress("10.215.173.1", 32);
        b.addRoute("0.0.0.0", 0);
        try {
            b.addAddress("fd66:f83a:c650::1", 128);
            b.addRoute("::", 0);
        } catch (IllegalArgumentException ignored) {
            // Ohne IPv6 bleibt die IPv4-Sperre trotzdem wirksam.
        }

        int added = 0;
        for (String pkg : blocked) {
            if (pkg.equals(getPackageName())) continue;
            try {
                b.addAllowedApplication(pkg);
                added++;
            } catch (PackageManager.NameNotFoundException ignored) {
                // App wurde deinstalliert.
            }
        }
        // Wichtig: Ohne erlaubte App wuerde das VPN fuer ALLE Apps gelten. Dann lieber gar nichts.
        if (added == 0) {
            shutdown();
            return;
        }

        Intent open = new Intent(this, MainActivity.class);
        b.setConfigureIntent(PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));

        ParcelFileDescriptor next;
        try {
            next = b.establish();
        } catch (RuntimeException e) {
            next = null;
        }
        if (next == null) {
            // Keine VPN-Erlaubnis (mehr), z. B. weil eine andere VPN-App uebernommen hat.
            Store.lastError = getString(R.string.err_permission);
            closeTun();
            Store.changed();
            stopSelf();
            return;
        }

        // establish() ersetzt den alten Tunnel; der alte Deskriptor kann danach zu.
        ParcelFileDescriptor old = tun;
        tun = next;
        if (old != null) {
            try { old.close(); } catch (IOException ignored) { }
        }
        // Der Tunnel wird bewusst nicht gelesen: Pakete der gesperrten Apps bleiben einfach liegen
        // und werden vom Kernel verworfen. Das kostet keinen Akku.
        Store.tunnelUp = true;
        Store.lastError = null;
        Store.changed();
    }

    private void closeTun() {
        if (tun != null) {
            try { tun.close(); } catch (IOException ignored) { }
            tun = null;
        }
        Store.tunnelUp = false;
    }

    private void shutdown() {
        closeTun();
        Store.changed();
        stopSelf();
    }

    @Override
    public void onRevoke() {
        // Eine andere VPN-App hat uebernommen oder der Nutzer hat das VPN in den Einstellungen getrennt.
        Store.get(this).setEnabled(false);
        Store.lastError = null;
        shutdown();
    }

    @Override
    public void onDestroy() {
        closeTun();
        Store.changed();
        super.onDestroy();
    }
}
