package io.cleanyourtv.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Connects the Trakt account: a code on the TV, typed at trakt.tv/activate on the phone (or the QR code). Signed in,
 * it shows the account and a button to disconnect.
 */
public class TraktLoginActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private float k;
    private LinearLayout box;
    private TextView status;
    private volatile boolean polling;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        k = TvUi.k(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(TvUi.BG);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        if (!Trakt.configured()) showMissing();
        else if (Trakt.signedIn(this)) showSignedIn();
        else start();
    }

    @Override protected void onDestroy() {
        polling = false;
        super.onDestroy();
    }

    private TextView line(float size, int color, String text) {
        TextView t = TvUi.text(this, k, size, size > 40 ? Ui.displaySemibold : Ui.regular, color);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Math.round(14 * k), 0, Math.round(14 * k));
        return t;
    }

    private void showMissing() {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, "Trakt"));
        box.addView(line(28, TvUi.DIM, Ui.tr("Adicione trakt_client_id e trakt_client_secret ao config.json e envie com push-config.sh.",
                "Add trakt_client_id and trakt_client_secret to config.json and send it with push-config.sh.")));
    }

    private void showSignedIn() {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Ui.tr("Conta Trakt conectada", "Trakt account connected")));
        String name = Trakt.userName(this);
        if (!name.isEmpty()) box.addView(line(30, TvUi.DIM, name));
        box.addView(line(26, TvUi.DIM, Ui.tr("A seguir e Minha lista aparecem como abas na tela inicial. Segure OK num cartão para marcar como visto, avaliar ou salvar.",
                "Up Next and Watchlist are tabs on the home screen. Hold OK on a card to mark it watched, rate it or save it.")));
        TextView out = TvUi.button(this, k, Ui.tr("Desconectar", "Disconnect"), v -> {
            Trakt.signOut(this);
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(30 * k);
        box.addView(out, lp);
        out.requestFocus();
    }

    private void start() {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Ui.tr("Conectar sua conta Trakt", "Connect your Trakt account")));
        status = line(28, TvUi.DIM, Ui.tr("Preparando…", "Getting ready…"));
        box.addView(status);
        new Thread(() -> {
            try {
                JSONObject d = Trakt.deviceCode();
                String code = d.getString("user_code"), url = d.optString("verification_url", "https://trakt.tv/activate");
                Bitmap qr = render(Qr.encode(url), Math.round(300 * k));
                main.post(() -> showCode(url, code, qr));
                polling = true;
                long until = System.currentTimeMillis() + d.optLong("expires_in", 600) * 1000;
                long wait = Math.max(5, d.optLong("interval", 5)) * 1000;
                while (polling && System.currentTimeMillis() < until) {
                    Thread.sleep(wait);
                    int r = Trakt.poll(this, d.getString("device_code"));
                    if (r == 1) {
                        Trakt.refresh(this, true, null);
                        main.post(this::showSignedIn);
                        return;
                    }
                    if (r < 0) break;
                }
                main.post(() -> status.setText(Ui.tr("O código expirou. Volte e abra de novo.", "The code expired. Go back and open it again.")));
            } catch (Exception e) {
                main.post(() -> status.setText(Ui.tr("Não deu para conectar: ", "Couldn't connect: ") + e.getMessage()));
            }
        }, "trakt-login").start();
    }

    private void showCode(String url, String code, Bitmap qr) {
        box.removeAllViews();
        box.addView(line(56, TvUi.INK, Ui.tr("Conectar sua conta Trakt", "Connect your Trakt account")));
        box.addView(line(28, TvUi.DIM, Ui.tr("No celular, abra o endereço abaixo (ou aponte a câmera), entre no Trakt e digite o código.",
                "On your phone, open the address below (or point the camera), sign in to Trakt and type the code.")));
        ImageView img = new ImageView(this);
        img.setImageBitmap(qr);
        img.setBackgroundColor(Color.WHITE);
        int pad = Math.round(20 * k);
        img.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(qr.getWidth() + 2 * pad, qr.getHeight() + 2 * pad);
        lp.topMargin = Math.round(16 * k);
        lp.bottomMargin = Math.round(10 * k);
        box.addView(img, lp);
        box.addView(line(28, TvUi.DIM, url.replace("https://", "")));
        TextView c = TvUi.text(this, k, 96, Ui.displaySemibold, TvUi.INK);
        c.setText(code);
        c.setLetterSpacing(0.12f);
        c.setGravity(Gravity.CENTER);
        box.addView(c);
        status = line(26, TvUi.FAINT, Ui.tr("Aguardando o código…", "Waiting for the code…"));
        box.addView(status);
    }

    private static Bitmap render(Qr q, int px) {
        int scale = Math.max(1, px / q.size);
        Bitmap b = Bitmap.createBitmap(q.size * scale, q.size * scale, Bitmap.Config.RGB_565);
        for (int y = 0; y < q.size; y++) {
            for (int x = 0; x < q.size; x++) {
                int color = q.dark(x, y) ? Color.BLACK : Color.WHITE;
                for (int dy = 0; dy < scale; dy++) for (int dx = 0; dx < scale; dx++) b.setPixel(x * scale + dx, y * scale + dy, color);
            }
        }
        return b;
    }
}
