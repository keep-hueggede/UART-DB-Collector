package WebServer.Handlers;

import WebServer.AppSettings;
import WebServer.Http.Handler;
import WebServer.Http.HttpCall;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * GET / PUT /api/admin/settings (admin) - settings stored in the database.
 * The Google client secret is write-only: GET only says whether it is set,
 * PUT without "googleClientSecret" keeps the stored one.
 */
public class SettingsHandler implements Handler {

    private final AppSettings settings;

    public SettingsHandler(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public void handle(HttpCall call) throws IOException {
        if ("PUT".equals(call.method())) {
            if (!HandlerSupport.requireJson(call)) return;
            JsonNode body = call.jsonBody();
            if (body == null || !body.isObject()) {
                call.error(400, "json object expected");
                return;
            }
            JsonNode ttl = body.get("sessionTtlDays");
            if (ttl != null && !ttl.isNull() && !ttl.canConvertToInt()) {
                call.error(400, "sessionTtlDays must be a number");
                return;
            }
            String secret = text(body, "googleClientSecret");
            settings.update(text(body, "googleClientId"), secret == null || secret.isBlank() ? null : secret,
                    text(body, "googleRedirectUri"), ttl == null || ttl.isNull() ? null : ttl.asInt());
        }
        call.json(200, settings.view());
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }
}
