package com.macrotel.rapidstylers.media.controller;

import com.macrotel.rapidstylers.controller.ApiResponses;
import com.macrotel.rapidstylers.pojo.*;
import com.macrotel.rapidstylers.media.service.GalleryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The public photo gallery. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class GalleryController {
    @Autowired
    GalleryService galleryService;

    @GetMapping("/gallery")
    public ResponseEntity <BaseResponse> gallery(
            @RequestParam("category") String category,
            @RequestParam(value = "per_page", defaultValue = "12") int perPage,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "query", defaultValue = "") String query){
        BaseResponse baseResponse = galleryService.searchGallery(category, perPage, page, query);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}
