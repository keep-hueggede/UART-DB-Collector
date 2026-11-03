package WebServer.Handlers;

import WebServer.AppSettings;
import WebServer.GoogleAuth;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import WebServer.SetupService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/setup (public) - whether first-run setup is open. Only while it is
 * open, it also tells where the one-time code is and what to enter at Google.
 */
public class SetupStatusHandler implements Handler {

    private final SetupService setup;
    private final AppSettings settings;
    private final GoogleAuth google;

    public SetupStatusHandler(SetupService setup, AppSettings settings, GoogleAuth google) {
        this.setup = setup;
        this.settings = settings;
        this.google = google;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("setupRequired", setup.isRequired());
        if (setup.isRequired()) {
            Map<String, Object> current = settings.view();
            out.put("codeFile", setup.codeFile().toString());
            out.put("redirectUri", google.redirectUri(call.scheme(), call.header("Host")));
            out.put("googleClientId", current.get("googleClientId"));
            out.put("googleClientSecretSet", current.get("googleClientSecretSet"));
        }
        call.json(200, out);
    }
}
