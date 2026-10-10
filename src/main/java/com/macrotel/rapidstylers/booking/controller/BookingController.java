package com.macrotel.rapidstylers.booking.controller;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.booking.pojo.AppointmentActionData;
import com.macrotel.rapidstylers.booking.pojo.BookAppointmentData;
import com.macrotel.rapidstylers.booking.service.BookingService;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Booking estimates, booking an appointment and the accept / decline / complete / cancel workflow. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class BookingController {
    @Autowired
    BookingService bookingService;

    @PostMapping("/booking_estimate")
    public ResponseEntity<BaseResponse> bookingEstimate(@RequestBody BookAppointmentData bookAppointmentData){
        BaseResponse baseResponse = bookingService.estimateBooking(bookAppointmentData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/book_appointment")
    public ResponseEntity <BaseResponse> bookAppointment(@Valid @RequestBody BookAppointmentData bookAppointmentData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        // The booking owner is the authenticated account — never trust the body's userId.
        bookAppointmentData.setUserId(accountId);
        String idempotencyKey = request.getHeader("Idempotency-Key");
        bookAppointmentData.setIdempotencyKey(idempotencyKey);
        BaseResponse baseResponse = bookingService.bookAppointment(bookAppointmentData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_appointments")
    public ResponseEntity<BaseResponse> stylerAppointments(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.stylerAppointments(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/accept_appointment")
    public ResponseEntity <BaseResponse> acceptAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.acceptAppointment(accountId, appointmentActionData.getAppointmentId(),
                appointmentActionData.getDecisionNote());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/decline_appointment")
    public ResponseEntity <BaseResponse> declineAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.declineAppointment(accountId, appointmentActionData.getAppointmentId(),
                appointmentActionData.getDecisionNote());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/complete_appointment")
    public ResponseEntity <BaseResponse> completeAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.completeAppointment(accountId, appointmentActionData.getAppointmentId());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/retry_appointment_payment")
    public ResponseEntity<BaseResponse> retryAppointmentPayment(@Valid @RequestBody AppointmentActionData action, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(bookingService.retryAppointmentPayment(accountId, action.getAppointmentId()));
    }

    @PostMapping("/cancel_appointment")
    public ResponseEntity <BaseResponse> cancelAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.cancelAppointment(accountId, appointmentActionData.getAppointmentId());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/styler_cancel_appointment")
    public ResponseEntity <BaseResponse> stylerCancelAppointment(@Valid @RequestBody AppointmentActionData appointmentActionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.stylerCancelAppointment(accountId, appointmentActionData.getAppointmentId());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/user_appointments")
    public ResponseEntity<BaseResponse> userAppointments(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.listUserAppointment(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/user_pending_appointments")
    public ResponseEntity<BaseResponse> userPendingAppointments(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = bookingService.listUserPendingAppointment(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}
