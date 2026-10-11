package com.macrotel.rapidstylers.styler.controller;

import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import com.macrotel.rapidstylers.styler.pojo.AvailabilityUpdateData;
import com.macrotel.rapidstylers.styler.pojo.ExceptionData;
import com.macrotel.rapidstylers.styler.service.AvailabilityService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Stylist weekly hours, days off and travel settings. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class AvailabilityController {
    @Autowired
    AvailabilityService availabilityService;

    @GetMapping("/styler_availability")
    public ResponseEntity<BaseResponse> stylerAvailability(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = availabilityService.stylerAvailability(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_styler_availability")
    public ResponseEntity<BaseResponse> updateStylerAvailability(@Valid @RequestBody AvailabilityUpdateData availabilityData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = availabilityService.updateStylerAvailability(accountId, availabilityData.getSlots());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler/travel_settings")
    public ResponseEntity<BaseResponse> stylerTravelSettings(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = availabilityService.stylerTravelSettings(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_styler_travel_settings")
    public ResponseEntity<BaseResponse> updateStylerTravelSettings(@RequestBody Map<String, Object> body, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        Object included = body.get("includedTravelKm");
        Double includedKm = included == null ? null : Double.valueOf(included.toString());
        String fee = body.get("baseTravelFee") == null ? null : String.valueOf(body.get("baseTravelFee"));
        AvailabilityService.LocationOptions options = null;
        if(body.containsKey("offersSalonVisits") || body.containsKey("offersHomeVisits") || body.containsKey("maxTravelKm")){
            options = new AvailabilityService.LocationOptions();
            options.offersSalonVisits = flag(body.get("offersSalonVisits"));
            options.offersHomeVisits = flag(body.get("offersHomeVisits"));
            Object maxKm = body.get("maxTravelKm");
            options.clearMaxTravelKm = body.containsKey("maxTravelKm") && (maxKm == null || maxKm.toString().isBlank());
            options.maxTravelKm = options.clearMaxTravelKm || maxKm == null ? null : Double.valueOf(maxKm.toString());
        }
        BaseResponse baseResponse = availabilityService.updateStylerTravelSettings(accountId, includedKm, fee, options);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/styler_availability_exceptions")
    public ResponseEntity<BaseResponse> stylerAvailabilityExceptions(HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = availabilityService.stylerAvailabilityExceptions(accountId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/add_availability_exception")
    public ResponseEntity<BaseResponse> addAvailabilityException(@Valid @RequestBody ExceptionData exceptionData, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = availabilityService.addAvailabilityException(accountId, exceptionData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/delete_availability_exception")
    public ResponseEntity<BaseResponse> deleteAvailabilityException(@RequestBody Map<String, Long> body, HttpServletRequest request){
        String accountId = CurrentAccount.id(request);
        if(accountId == null){
            return CurrentAccount.unauthorized();
        }
        BaseResponse baseResponse = availabilityService.deleteAvailabilityException(accountId, body.get("exceptionId"));
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    private static Boolean flag(Object raw){
        return raw == null ? null : Boolean.valueOf(raw.toString());
    }
}
