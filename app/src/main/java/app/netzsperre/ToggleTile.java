package app.netzsperre;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Kachel in den Schnelleinstellungen: Sperre mit einem Tipp an und aus. */
public class ToggleTile extends TileService implements Store.Listener {

    @Override
    public void onStartListening() {
        Store.addListener(this);
        update();
    }

    @Override
    public void onStopListening() {
        Store.removeListener(this);
    }

    @Override
    public void onStoreChanged() {
        update();
    }

    @Override
    public void onClick() {
        Store s = Store.get(this);
        if (s.enabled()) {
            s.setEnabled(false);
            BlockVpnService.apply(this);
        } else if (s.blocked().isEmpty() || VpnService.prepare(this) != null) {
            // Noch nichts ausgewaehlt oder Erlaubnis fehlt: App oeffnen.
            openApp();
        } else {
            s.setEnabled(true);
            BlockVpnService.apply(this);
        }
        update();
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_ENABLE, true);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        Store s = Store.get(this);
        boolean on = s.enabled();
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) {
            int n = s.blocked().size();
            t.setSubtitle(on ? getResources().getQuantityString(R.plurals.tile_blocked, n, n)
                    : getString(R.string.tile_off));
        }
        t.updateTile();
    }
}
