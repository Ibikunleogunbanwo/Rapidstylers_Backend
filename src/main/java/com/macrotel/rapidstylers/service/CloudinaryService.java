package com.macrotel.rapidstylers.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Destroys assets on Cloudinary on the application's own behalf.
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
     * this account can delete. Group 1 is the cloud name and group 2 the public id.
     */
    private static final Pattern DELIVERY_URL = Pattern.compile(
            "^https?://res\\.cloudinary\\.com/([^/]+)/image/upload/(?:v\\d+/)?(.+)$");

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
        String publicId = matcher.group(2);
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
            String signature = sha1("public_id=" + publicId + "&timestamp=" + timestamp + apiSecret);

            LinkedMultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("public_id", publicId);
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

    private String sha1(String input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        byte[] hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte b : hashed) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
