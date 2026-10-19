package WebServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Google OAuth 2.0 / OpenID Connect (authorization code flow).
 *
 * Identity is verified server-to-server against Google's OIDC userinfo
 * endpoint over TLS using the access token from the code exchange - no
 * user registration, no local passwords. Requires GOOGLE.CLIENT_ID and
 * GOOGLE.CLIENT_SECRET ("OAuth 2.0 Client ID" of type Web application in
 * the Google Cloud Console).
 *
 * The redirect URI is taken from GOOGLE.REDIRECT_URI if configured,
 * otherwise derived from the request Host header. Google itself enforces
 * the redirect allow-list, so a forged Host can never receive codes.
 */
public class GoogleAuth {

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_ENDPOINT = "https://openidconnect.googleapis.com/v1/userinfo";

    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9.\\-]+(:[0-9]+)?");

    private final String clientId;
    private final String clientSecret;
    private final String configuredRedirectUri;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public GoogleAuth(String clientId, String clientSecret, String configuredRedirectUri) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.configuredRedirectUri = configuredRedirectUri == null ? "" : configuredRedirectUri.trim();
    }

    public boolean isConfigured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** The redirect URI for a given request (config override or request host). */
    public String redirectUri(String scheme, String hostHeader) {
        if (!configuredRedirectUri.isEmpty()) return configuredRedirectUri;
        String schemeNorm = (scheme == null || scheme.isEmpty()) ? "http" : scheme;
        String host = hostHeader == null ? "" : hostHeader.trim();
        if (!SAFE_HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("invalid Host header");
        }
        return schemeNorm + "://" + host + "/auth/callback";
    }

    /** Authorization URL the browser is redirected to. */
    public String loginUrl(String redirectUri, String state) {
        return AUTH_ENDPOINT
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc("openid email profile")
                + "&state=" + enc(state)
                + "&prompt=select_account";
    }

    /** Verified identity from Google (userinfo endpoint, TLS + aud/iss checked by Google). */
    public static class GoogleUser {
        public final String sub;
        public final String email;
        public final String name;
        public final String pictureUrl;

        GoogleUser(String sub, String email, String name, String pictureUrl) {
            this.sub = sub;
            this.email = email;
            this.name = name;
            this.pictureUrl = pictureUrl;
        }
    }

    /**
     * Exchanges the authorization code and verifies the identity via the
     * userinfo endpoint. Throws IOException on any failure - the caller
     * treats that as "login failed" and never creates a session.
     */
    public GoogleUser exchangeCode(String code, String redirectUri) throws IOException, InterruptedException {
        String body = "code=" + enc(code)
                + "&client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&redirect_uri=" + enc(redirectUri)
                + "&grant_type=authorization_code";

        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            // never echo the response body - it can contain request details
            throw new IOException("token exchange failed (HTTP " + res.statusCode() + ")");
        }

        JsonNode tok = json.readTree(res.body());
        String accessToken = tok.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new IOException("no access token in response");
        }

        JsonNode idTokenClaims = tok.has("id_token") ? parseJwtPayload(tok.get("id_token").asText()) : null;
        String idTokenSub = idTokenClaims == null ? null : idTokenClaims.path("sub").asText(null);
        String idTokenAud = idTokenClaims == null ? null : idTokenClaims.path("aud").asText(null);
        if (idTokenClaims != null) {
            // basic claims check on the id_token as well (in addition to userinfo)
            String iss = idTokenClaims.path("iss").asText("");
            long exp = idTokenClaims.path("exp").asLong(0);
            boolean issOk = iss.equals("https://accounts.google.com") || iss.equals("accounts.google.com");
            boolean audOk = idTokenAud == null || idTokenAud.equals(clientId);
            if (!issOk || !audOk || exp * 1000L < System.currentTimeMillis()) {
                throw new IOException("id_token claim validation failed");
            }
        }

        GoogleUser user = userinfo(accessToken);
        if (user == null) throw new IOException("userinfo request failed");
        if (idTokenSub != null && !idTokenSub.equals(user.sub)) {
            throw new IOException("sub mismatch between id_token and userinfo");
        }
        return user;
    }

    private GoogleUser userinfo(String accessToken) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(USERINFO_ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;

        JsonNode info = json.readTree(res.body());
        String sub = info.path("sub").asText("");
        boolean verified = info.path("email_verified").asBoolean(false);
        String email = info.path("email").asText("");
        if (sub.isEmpty() || !verified || email.isEmpty()) {
            // only verified Google accounts may get a session
            throw new IOException("Google account is not verified or incomplete");
        }
        String name = info.path("name").asText(email);
        String picture = info.path("picture").asText("");
        return new GoogleUser(sub, email, name, picture);
    }

    /** Extracts the payload of a JWT without verifying the signature (signature
     *  authenticity is established by the TLS call to Google's userinfo endpoint;
     *  the id_token claims are only used as an additional consistency check). */
    private static JsonNode parseJwtPayload(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return null;
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return new ObjectMapper().readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
