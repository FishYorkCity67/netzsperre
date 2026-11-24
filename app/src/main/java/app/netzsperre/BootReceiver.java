package app.netzsperre;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;

/** Schaltet die Sperre nach einem Neustart oder einem App-Update wieder ein. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Store s = Store.get(context);
        if (!s.enabled() || s.blocked().isEmpty()) return;
        if (VpnService.prepare(context) != null) return; // Erlaubnis fehlt, App muss einmal geoeffnet werden.
        BlockVpnService.apply(context);
    }
}
