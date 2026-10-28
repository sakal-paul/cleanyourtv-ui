package io.cleanyourtv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The home screen (Android's "Home" role, set from the computer by launcher-ui/build-and-install.sh). It runs in the
 * :home process. If it ever fails, tools/restore.sh gives the home screen back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private ServicePanel panel;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String panelNext;
    /** Resting on a service's tile for a moment shows its panel (sliding past it doesn't). */
    private final Runnable showPanel = () -> showPanel(panelNext);

    /** The panel for this service (null: none), with the home screen's own title, cards and buttons hidden under it. */
    private void showPanel(String pkg) {
        if (panel == null) return;
        panel.setNight(home.night());
        panel.show(pkg);
        home.setTopHidden(panel.isShowing());
    }
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        home = new HomeView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        panel = new ServicePanel(this, () -> home.setShelfFocusHidden(false));
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(ServicePanel.HEIGHT * TvUi.k(this))));
        setContentView(root);
        home.focusListener = this::followFocus;
        home.toneListener = () -> panel.setNight(home.night());
        getWindow().setBackgroundDrawable(null);
        openFrom(getIntent());
    }

    @Override protected void onResume() {
        super.onResume();
        TvConfig.apply(this);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        Images.trimSoon();   // Explore's caches, if it was open: Home doesn't use them
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (panel != null) {
            main.removeCallbacks(showPanel);
            showPanel(null);
            home.setShelfFocusHidden(false);
        }
        if (home != null) home.reset(true);
        openFrom(intent);
    }

    /** `--es open explore|watchlist`: Explore from the computer (adb), as the remote's buttons open it. */
    private void openFrom(Intent intent) {
        String what = intent == null ? null : intent.getStringExtra("open");
        if (what == null) return;
        intent.removeExtra("open");
        if (what.equals("explore")) RemoteKeys.run(this, "tmdb:explore");
        else if (what.equals("watchlist")) RemoteKeys.run(this, "tmdb:watchlist");
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    /** The app row's focus moved: show the focused service's panel (after a moment), or hide it. */
    private void followFocus() {
        if (panel == null || panel.isActive()) return;
        String pkg = home.shelfFocusPackage();
        main.removeCallbacks(showPanel);
        if (!ServicePanel.isService(pkg)) {
            showPanel(null);
        } else if (!pkg.equals(panel.service())) {
            panelNext = pkg;
            main.postDelayed(showPanel, panel.isShowing() ? 250 : 400);
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (panel != null && panel.onKey(event)) return true;
        if (panel != null && panel.isShowing() && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                && panel.service().equals(home.shelfFocusPackage())) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || panel.enter()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) home.setShelfFocusHidden(true);
                return true;
            }
        }
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
