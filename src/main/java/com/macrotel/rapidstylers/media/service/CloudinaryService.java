package com.macrotel.rapidstylers.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

/**
 * Destroys assets on Cloudinary on the application's own behalf, and signs the
 * short-lived links an admin uses to view a private ID photo.
 *
 * The upload path already existed: the frontend is handed a signed parameter set
 * and posts the bytes straight to Cloudinary, so this server never carries image
 * data. The consequence is that the server only learns an asset's identity when
 * the row that stores its URL is written, which is why deletion belongs here:
 * when a portfolio photo is removed or a cover is replaced, the asset that was
 * left behind has to be destroyed by whoever removed the row, not by a browser
 * that may never come back.
 *
 * Nothing in this class throws. Cleanup runs after the user's operation has
 * already succeeded or failed on its own terms, and a Cloudinary outage must not
 * turn "photo removed" into "something went wrong".
 */
@Service
public class CloudinaryService {

    private static final Logger LOG = Logger.getLogger(CloudinaryService.class.getName());

    @Value("${cloudinary.cloud-name:}")
    private String cloudName;

    @Value("${cloudinary.api-key:}")
    private String apiKey;

    @Value("${cloudinary.api-secret:}")
    private String apiSecret;

    /**
     * The delivery URL shape this application stores, which is the `secure_url`
     * Cloudinary returns:
     * `https://res.cloudinary.com/<cloud>/image/upload/[v1234567890/]<public_id>.<ext>`.
     *
     * The host and the `/image/upload/` path are anchored deliberately. A stored
     * value is allowed to be something else entirely, a static path under the app
     * (`/images/gallery/locs-2.jpg`), an image hosted elsewhere, or a URL that
     * merely contains those words, and none of them may be mistaken for an asset
     * this account can delete. Group 1 is the cloud name, group 2 the delivery
     * type (public `upload`, or private `authenticated` with a `s--SIG--` path
     * signature) and group 3 the public id.
     */
    private static final Pattern DELIVERY_URL = Pattern.compile(
            "^https?://res\\.cloudinary\\.com/([^/]+)/image/(upload|authenticated)/(?:s--[^/]+--/)?(?:v\\d+/)?(.+)$");

    /** Delivery type of an ordinary, public upload. */
    public static final String TYPE_UPLOAD = "upload";

    /**
     * Delivery type of a private asset (ID photos): Cloudinary serves it only
     * through a signed URL. The secure_url returned on upload carries a
     * permanent `s--SIG--` path signature, so that stored URL must not be shown
     * to anyone (see {@link #withoutDeliverySignature}).
     */
    public static final String TYPE_AUTHENTICATED = "authenticated";

    /** The `s--SIG--/` path segment of a signed delivery URL. */
    private static final Pattern PATH_SIGNATURE = Pattern.compile("/s--[^/]+--/");

    /**
     * The public id inside a stored delivery URL, or null when the URL is not one
     * of this account's Cloudinary delivery URLs (or is unusable).
     */
    public String extractPublicId(String url) {
        if (isBlank(url)) {
            return null;
        }
        Matcher matcher = DELIVERY_URL.matcher(url.trim());
        if (!matcher.find()) {
            return null;
        }
        String cloud = matcher.group(1);
        // A URL belonging to another Cloudinary account is not ours to delete,
        // even though it has the same shape.
        if (!isBlank(cloudName) && !cloudName.equals(cloud)) {
            return null;
        }
        String publicId = matcher.group(3);
        int query = publicId.indexOf('?');
        if (query >= 0) {
            publicId = publicId.substring(0, query);
        }
        int extension = publicId.lastIndexOf('.');
        if (extension > 0) {
            publicId = publicId.substring(0, extension);
        }
        return isBlank(publicId) ? null : publicId;
    }

    /**
     * The delivery type of a stored delivery URL ({@link #TYPE_UPLOAD} or
     * {@link #TYPE_AUTHENTICATED}), or null when it is not one of ours.
     */
    public String deliveryType(String url) {
        if (extractPublicId(url) == null) {
            return null;
        }
        Matcher matcher = DELIVERY_URL.matcher(url.trim());
        return matcher.find() ? matcher.group(2) : null;
    }

    /** The file extension of a stored delivery URL (png, jpg...), or null when it has none. */
    public static String fileFormat(String url) {
        if (isBlank(url)) {
            return null;
        }
        String path = url.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        String format = dot > 0 ? last.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        return format.matches("[a-z0-9]{1,5}") ? format : null;
    }

    /**
     * The URL with any `s--SIG--` path signature removed. For a private asset
     * that leaves a link that no longer opens the image, which is what a list
     * row should carry: viewing goes through a short-lived signed link instead.
     * Other URLs come back unchanged.
     */
    public static String withoutDeliverySignature(String url) {
        if (url == null || !url.contains("res.cloudinary.com/")) {
            return url;
        }
        return PATH_SIGNATURE.matcher(url).replaceFirst("/");
    }

    /**
     * A private download link for one asset that stops working at expiresAt
     * (epoch seconds), or null when Cloudinary is not configured.
     *
     * This is Cloudinary's signed download API
     * (`api.cloudinary.com/v1_1/<cloud>/image/download`), chosen over a
     * `s--SIG--` signed delivery URL on purpose: a path signature never expires
     * (expiring delivery URLs need the paid token-authentication add-on), while
     * the download API checks `expires_at` itself, so a copied link is useless
     * after a few minutes. It also serves any delivery type, so older ID photos
     * stored as public uploads open the same way as new authenticated ones.
     *
     * Signed like every Cloudinary API call: the parameters other than api_key,
     * sorted by name, joined as name=value with '&', the API secret appended,
     * then SHA-1.
     */
    public String privateDownloadUrl(String publicId, String format, String type, long expiresAt) {
        if (isBlank(publicId) || !isConfigured()) {
            return null;
        }
        try {
            java.util.TreeMap<String, String> params = new java.util.TreeMap<>();
            params.put("public_id", publicId);
            if (!isBlank(format)) {
                params.put("format", format);
            }
            params.put("type", isBlank(type) ? TYPE_UPLOAD : type);
            params.put("expires_at", String.valueOf(expiresAt));
            params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
            String signature = sha1(signable(params) + apiSecret);

            StringBuilder url = new StringBuilder("https://api.cloudinary.com/v1_1/")
                    .append(cloudName).append("/image/download?api_key=").append(encode(apiKey));
            for (java.util.Map.Entry<String, String> param : params.entrySet()) {
                url.append('&').append(param.getKey()).append('=').append(encode(param.getValue()));
            }
            return url.append("&signature=").append(signature).toString();
        } catch (Exception ex) {
            LOG.warning("Could not sign a download link for " + publicId + ": " + ex.getMessage());
            return null;
        }
    }

    /** name=value pairs joined with '&', in the (sorted) order of the map: the string Cloudinary signs. */
    static String signable(java.util.SortedMap<String, String> params) {
        StringBuilder joined = new StringBuilder();
        for (java.util.Map.Entry<String, String> param : params.entrySet()) {
            if (joined.length() > 0) {
                joined.append('&');
            }
            joined.append(param.getKey()).append('=').append(param.getValue());
        }
        return joined.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Whether this account is configured to talk to Cloudinary at all. Without
     * credentials there is nothing to call, and saying so once is friendlier than
     * logging a failed HTTP attempt per image.
     */
    public boolean isConfigured() {
        return !isBlank(cloudName) && !isBlank(apiKey) && !isBlank(apiSecret);
    }

    /**
     * Blank-safe, because these values arrive from configuration and a component
     * built outside a Spring context holds nulls rather than empty strings.
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Destroys one asset by public id.
     *
     * @return true when Cloudinary reported the asset gone (including the case
     * where it was already gone, which is the state we wanted either way)
     */
    public boolean destroy(String publicId) {
        return destroy(publicId, TYPE_UPLOAD);
    }

    /**
     * Destroys one asset of the given delivery type. A private (authenticated)
     * asset is a different asset from a public one with the same public id, so
     * Cloudinary has to be told which one is meant.
     */
    public boolean destroy(String publicId, String type) {
        if (isBlank(publicId)) {
            return false;
        }
        if (!isConfigured()) {
            LOG.warning("Cloudinary is not configured, so " + publicId + " was not destroyed");
            return false;
        }
        try {
            long timestamp = Instant.now().getEpochSecond();
            // Cloudinary signs the destroy params (public_id + timestamp), sorted
            // alphabetically, with the API secret appended, then SHA-1.
            // A public upload is signed exactly as before; any other type is
            // sent, and so signed, as one more parameter.
            boolean typed = !isBlank(type) && !TYPE_UPLOAD.equals(type);
            String signature = sha1("public_id=" + publicId + "&timestamp=" + timestamp
                    + (typed ? "&type=" + type : "") + apiSecret);

            LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("public_id", publicId);
            if (typed) {
                body.add("type", type);
            }
            body.add("api_key", apiKey);
            body.add("timestamp", String.valueOf(timestamp));
            body.add("signature", signature);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

            ResponseEntity<String> cloudRes = restTemplate().postForEntity(
                    URI.create("https://api.cloudinary.com/v1_1/" + cloudName + "/image/destroy"),
                    new HttpEntity<>(body, headers),
                    String.class);

            String responseBody = cloudRes.getBody();
            JsonNode root = responseBody == null
                    ? null
                    : new ObjectMapper().readTree(responseBody);
            String result = root == null ? "" : root.path("result").asText("");
            if ("ok".equals(result)) {
                LOG.info("Destroyed Cloudinary asset " + publicId);
                return true;
            }
            if ("not found".equals(result)) {
                // Already gone is the outcome we were after, so it is not a failure.
                LOG.info("Cloudinary asset " + publicId + " was already gone");
                return true;
            }
            LOG.warning("Cloudinary refused to destroy " + publicId + ": " + responseBody);
            return false;
        } catch (Exception ex) {
            LOG.warning("Cloudinary destroy failed for " + publicId + ": " + ex.getMessage());
            return false;
        }
    }

    /** One uploaded asset as the listing reports it. */
    public static final class Upload {
        public final String publicId;
        public final Instant createdAt;
        public final String type;

        public Upload(String publicId, Instant createdAt) {
            this(publicId, createdAt, TYPE_UPLOAD);
        }

        public Upload(String publicId, Instant createdAt, String type) {
            this.publicId = publicId;
            this.createdAt = createdAt;
            this.type = type;
        }
    }

    /** A page of uploads plus the cursor for the next page (null on the last). */
    public static final class UploadPage {
        public final java.util.List<Upload> uploads;
        public final String nextCursor;

        public UploadPage(java.util.List<Upload> uploads, String nextCursor) {
            this.uploads = uploads;
            this.nextCursor = nextCursor;
        }
    }

    /**
     * Lists uploaded images under a folder, oldest pages first as Cloudinary
     * returns them, 500 at a time. Returns an empty page when Cloudinary is not
     * configured or the call fails, so a caller simply finds nothing to do.
     */
    public UploadPage listUploads(String prefix, String cursor) {
        return listUploads(prefix, cursor, TYPE_UPLOAD);
    }

    /** {@link #listUploads(String, String)} for one delivery type (private ID photos are `authenticated`). */
    public UploadPage listUploads(String prefix, String cursor, String type) {
        if (!isConfigured() || isBlank(prefix) || isBlank(type) || !type.matches("[a-z]+")) {
            return new UploadPage(Collections.emptyList(), null);
        }
        try {
            String url = "https://api.cloudinary.com/v1_1/" + cloudName + "/resources/image/" + type
                    + "?max_results=500&prefix="
                    + java.net.URLEncoder.encode(prefix, StandardCharsets.UTF_8)
                    + (isBlank(cursor) ? "" : "&next_cursor=" + java.net.URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.setBasicAuth(apiKey, apiSecret);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            ResponseEntity<String> res = restTemplate().exchange(URI.create(url),
                    org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers), String.class);
            JsonNode root = res.getBody() == null ? null : new ObjectMapper().readTree(res.getBody());
            java.util.List<Upload> uploads = new java.util.ArrayList<>();
            if (root != null) {
                for (JsonNode resource : root.path("resources")) {
                    String publicId = resource.path("public_id").asText("");
                    String created = resource.path("created_at").asText("");
                    if (!publicId.isBlank() && !created.isBlank()) {
                        uploads.add(new Upload(publicId, Instant.parse(created), type));
                    }
                }
            }
            String next = root == null ? "" : root.path("next_cursor").asText("");
            return new UploadPage(uploads, next.isBlank() ? null : next);
        } catch (Exception ex) {
            LOG.warning("Cloudinary listing failed for " + prefix + ": " + ex.getMessage());
            return new UploadPage(Collections.emptyList(), null);
        }
    }

    /**
     * A short-timeout client. A browser is often waiting on the request that
     * triggered this cleanup, so it must not hang on a slow third party.
     */
    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    private static String sha1(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : hashed) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
