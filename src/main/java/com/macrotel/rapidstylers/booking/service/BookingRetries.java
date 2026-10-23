package com.macrotel.rapidstylers.booking.service;

import com.macrotel.rapidstylers.booking.entity.BookAppointmentEntity;
import com.macrotel.rapidstylers.booking.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.SUCCESS_STATUS_CODE;

/**
 * Makes "book an appointment" safe to retry when the answer gets lost.
 *
 * <p>The client sends an Idempotency-Key with the request. The key is saved on
 * the booking row itself, behind a unique index, together with a fingerprint of
 * what was asked for. Because key and booking live in one row they commit or roll
 * back together: a key never points at a booking that does not exist, and a
 * request that failed leaves the key free for the next try.
 *
 * <ul>
 *   <li>Same key, same request, booking exists: answer as the first time did, with the same booking id.</li>
 *   <li>Same key, different request or different account: refuse (422).</li>
 *   <li>Two requests with one key at once: the unique index makes the second wait
 *       for the first, then it finds the booking and replays it.</li>
 * </ul>
 */
@Service
public class BookingRetries {

    private static final Logger LOG = Logger.getLogger(BookingRetries.class.getName());
    static final int MAX_KEY_LENGTH = 100;
    static final String KEY_REUSED =
            "This Idempotency-Key was already used for a different booking request. Use a new key.";

    @Autowired
    BookingService bookingService;
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;

    public BaseResponse book(BookAppointmentData data) {
        String key = data.getIdempotencyKey() == null ? "" : data.getIdempotencyKey().trim();
        if (key.isEmpty()) {
            data.setIdempotencyKey(null);
            return bookingService.bookAppointment(data);
        }
        if (key.length() > MAX_KEY_LENGTH) {
            return Responses.errorResponse(new BaseResponse(true), "Idempotency-Key is too long");
        }
        data.setIdempotencyKey(key);
        String fingerprint = fingerprint(data);

        Optional<BaseResponse> earlier = answerFor(key, data.getUserId(), fingerprint);
        if (earlier.isPresent()) {
            return earlier.get();
        }
        BaseResponse response;
        try {
            response = bookingService.bookAppointment(data, fingerprint);
        } catch (DataIntegrityViolationException ex) {
            // Usually a twin request with this key committed first; otherwise the
            // slot was taken and the conflict answer stands.
            return answerFor(key, data.getUserId(), fingerprint).orElseThrow(() -> ex);
        }
        if (SUCCESS_STATUS_CODE.equals(response.getStatusCode())) {
            return response;
        }
        // A twin that held the stylist lock first may have booked it.
        return answerFor(key, data.getUserId(), fingerprint).orElse(response);
    }

    /** The answer owed to a request whose key already has a booking, if one does. */
    private Optional<BaseResponse> answerFor(String key, String userId, String fingerprint) {
        Optional<BookAppointmentEntity> booked = bookAppointmentRepo.findByIdempotencyKey(key);
        if (booked.isEmpty()) {
            return Optional.empty();
        }
        BookAppointmentEntity booking = booked.get();
        if (!booking.getUserId().equals(userId) || !fingerprint.equals(booking.getIdempotencyFingerprint())) {
            BaseResponse refused = Responses.errorResponse(new BaseResponse(true), KEY_REUSED);
            refused.setStatusCode("422");
            return Optional.of(refused);
        }
        LOG.info("Replaying booking " + booking.getAppointmentId() + " for a retried request");
        return Optional.of(BookingService.bookedResponse(booking.getAppointmentId()));
    }

    /**
     * What the request asks for, as a SHA-256. The card is left out on purpose: it
     * is how the customer pays, not what they book, and a retry may carry a fresh one.
     */
    static String fingerprint(BookAppointmentData data) {
        String canonical = String.join("\n",
                part(data.getUserId()),
                part(data.getStylerId()),
                part(data.getSubServiceId()),
                part(data.getAppointmentDate()),
                part(data.getArrivalTime()),
                part(data.getServiceTime()),
                part(data.getNoOfPeople()),
                part(data.getServiceAddress()),
                part(data.getTravelDistanceKm()))
                // Added only when present, so fingerprints saved before promo codes still match.
                + (data.getPromoCode() == null || data.getPromoCode().isBlank() ? "" : "\npromo:" + part(data.getPromoCode()).toUpperCase())
                + (data.getQuoteRef() == null || data.getQuoteRef().isBlank() ? "" : "\nquote:" + part(data.getQuoteRef()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is always available", ex);
        }
    }

    private static String part(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
