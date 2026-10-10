package com.macrotel.rapidstylers.styler.controller;

import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.pojo.SearchNearbyData;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.styler.service.GeocodingService;
import com.macrotel.rapidstylers.styler.service.SearchService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Finding stylists, plus the address and location lookups the search box uses. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class SearchController {
    @Autowired
    SearchService searchService;
    @Autowired
    GeocodingService geocodingService;

    @GetMapping("/list_all_stylers")
    public ResponseEntity <BaseResponse> listAllStylers(){
        BaseResponse baseResponse = searchService.listAllStylers();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/search_styler")
    public ResponseEntity <BaseResponse> searchStyler(@RequestParam("businessName") String businessName){
        BaseResponse baseResponse = searchService.searchStyler(businessName);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/search_by_province")
    public ResponseEntity <BaseResponse> searchByProvince(@RequestParam("province") String province){
        BaseResponse baseResponse = searchService.searchStylerByProvince(province);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/search_by_city")
    public ResponseEntity <BaseResponse> searchByCity(@RequestParam("city") String city){
        BaseResponse baseResponse = searchService.searchStylerByCity(city);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/search_nearby")
    public ResponseEntity <BaseResponse> searchNearby(@RequestBody SearchNearbyData data){
        BaseResponse baseResponse = searchService.searchNearby(data.getLng(), data.getLat(), data.getRadius(), data.getServiceTypeId(), data.getCity(), data.getRequestedDate(), data.getRequestedTime(), data.getDurationMinutes(), data.isOpenNow(), data.getPage(), data.getPageSize());
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/place_autocomplete")
    public ResponseEntity<BaseResponse> placeAutocomplete(@RequestParam("input") String input){
        BaseResponse baseResponse = new BaseResponse();
        baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
        baseResponse.setMessage(SUCCESS_MESSAGE);
        baseResponse.setData(geocodingService.placeAutocomplete(input));
        return ApiResponses.respond(baseResponse);
    }

    @GetMapping("/place_details")
    public ResponseEntity<BaseResponse> placeDetails(@RequestParam("placeId") String placeId){
        BaseResponse baseResponse = new BaseResponse();
        Map<String, Object> data = geocodingService.geocodeByPlaceId(placeId);
        if (data == null) {
            baseResponse.setStatusCode(ERROR_STATUS_CODE);
            baseResponse.setMessage("Place not found");
            baseResponse.setData(java.util.Collections.emptyList());
        } else {
            baseResponse.setStatusCode(SUCCESS_STATUS_CODE);
            baseResponse.setMessage(SUCCESS_MESSAGE);
            baseResponse.setData(data);
        }
        return ApiResponses.respond(baseResponse);
    }

    @GetMapping("/detect-location")
    public ResponseEntity <BaseResponse> detectLocation(javax.servlet.http.HttpServletRequest request){
        BaseResponse baseResponse = searchService.detectLocation(request);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/reverse-geocode")
    public ResponseEntity <BaseResponse> reverseGeocode(@RequestBody Map<String, Double> body){
        double lat = body.getOrDefault("lat", 0.0);
        double lng = body.getOrDefault("lng", 0.0);
        BaseResponse baseResponse = searchService.reverseGeocode(lat, lng);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/search_by_service")
    public ResponseEntity<BaseResponse> stylerByService(@RequestParam("serviceTypeId") String serviceTypeId){
        BaseResponse baseResponse = searchService.getStylerByService(serviceTypeId);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}
