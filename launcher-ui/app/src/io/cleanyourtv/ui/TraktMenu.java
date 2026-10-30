package io.cleanyourtv.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Trakt menu for a title (OK held for a second on a card, on the home screen or a service's page): Add to or
 * Remove from watchlist, Mark as watched (for an Up Next show, just its next episode), Rate, Close.
 */
final class TraktMenu {
    /** How long OK is held for the menu (let go after it), and for the title's page (opens while still held). */
    static final long MENU_MS = 1000, DETAILS_MS = 2500;

    private TraktMenu() {}

    /**
     * Shows the menu over `anchor` (window coordinates). `note` is the card's Trakt note ("Next S2 E4") or null;
     * `toast` reports the result, `changed` runs once Trakt has the change (to refresh the tabs).
     */
    static void show(Activity a, View parent, Rect anchor, String kind, int id, String title, String note, boolean light,
                     Consumer<String> toast, Runnable changed) {
        Consumer<Boolean> done = ok -> {
            toast.accept(ok ? Ui.tr("Salvo no Trakt", "Saved to Trakt") : Ui.tr("O Trakt não respondeu. Tente de novo.", "Trakt didn't answer. Try again."));
            Trakt.refresh(a, true, changed);
        };
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean listed = Trakt.onWatchlist(kind, id);
        labels.add(listed ? Ui.tr("Tirar da minha lista", "Remove from watchlist") : Ui.tr("Pôr na minha lista", "Add to watchlist"));
        actions.add(() -> Trakt.setWatchlist(a, kind, id, !listed, done));
        long ep = kind.equals("tv") ? Trakt.nextEpisode(id) : 0;
        if (ep != 0) {
            String which = note == null ? "" : note.replaceFirst("^\\S+ ", "");
            labels.add(Ui.tr("Marcar ", "Mark ") + which + Ui.tr(" como visto", " as watched"));
            actions.add(() -> Trakt.markEpisode(a, ep, done));
        } else {
            labels.add(kind.equals("tv") ? Ui.tr("Marcar a série toda como vista", "Mark whole show as watched") : Ui.tr("Marcar como visto", "Mark as watched"));
            actions.add(() -> Trakt.markWatched(a, kind, id, done));
        }
        labels.add(Ui.tr("Avaliar", "Rate"));
        actions.add(() -> rate(a, parent, anchor, kind, id, title, light, done));
        labels.add(Ui.tr("Fechar", "Close"));
        actions.add(() -> {});
        TvUi.popover(a, parent, anchor, title, labels.toArray(new String[0]), which -> actions.get(which).run(), light);
    }

    private static void rate(Activity a, View parent, Rect anchor, String kind, int id, String title, boolean light, Consumer<Boolean> done) {
        int[] values = {10, 8, 6, 4, 2};
        String[] words = {Ui.tr("Amei", "Loved it"), Ui.tr("Gostei", "Liked it"), Ui.tr("Ok", "It was OK"),
                Ui.tr("Não gostei", "Didn't like it"), Ui.tr("Odiei", "Hated it")};
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        int mine = Trakt.rating(kind, id);
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            labels.add("★ " + v + "  " + words[i] + (mine == v ? "  ✓" : ""));
            actions.add(() -> Trakt.rate(a, kind, id, v, done));
        }
        labels.add(Ui.tr("Fechar", "Close"));
        actions.add(() -> {});
        TvUi.popover(a, parent, anchor, title, labels.toArray(new String[0]), which -> actions.get(which).run(), light);
    }
}
