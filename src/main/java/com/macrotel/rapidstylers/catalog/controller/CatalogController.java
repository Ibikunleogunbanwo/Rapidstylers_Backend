package com.macrotel.rapidstylers.catalog.controller;

import com.macrotel.rapidstylers.catalog.pojo.BlogPostData;
import com.macrotel.rapidstylers.catalog.pojo.IdentificationData;
import com.macrotel.rapidstylers.catalog.pojo.ServiceTypeData;
import com.macrotel.rapidstylers.catalog.service.CatalogService;
import com.macrotel.rapidstylers.controller.ApiResponses;
import com.macrotel.rapidstylers.pojo.BaseResponse;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Identification types, service types and blog posts. Same URLs as before, under /rapid_stylers.
 */
@RestController
@RequestMapping("/rapid_stylers")
public class CatalogController {
    @Autowired
    CatalogService catalogService;

    @PostMapping("/create_identification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> createIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = catalogService.createIdentificationType(identificationData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_identification")
    public ResponseEntity <BaseResponse> listIdentification(){
        BaseResponse baseResponse = catalogService.listIdentification();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/delete_identification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> deleteIdentification(@RequestBody Map<String, String> body){
        String id = body.getOrDefault("id", "");
        BaseResponse baseResponse = catalogService.deleteIdentification(id);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_identification")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateIdentification(@Valid @RequestBody IdentificationData identificationData){
        BaseResponse baseResponse = catalogService.updateIdentification(identificationData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_service")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> createService(@Valid @RequestBody ServiceTypeData serviceTypeData){
        BaseResponse baseResponse = catalogService.createServiceType(serviceTypeData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_service")
    public ResponseEntity <BaseResponse> listService(){
        BaseResponse baseResponse = catalogService.listService();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/delete_service")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> deleteService(@RequestBody Map<String, String> body){
        String id = body.getOrDefault("id", "");
        BaseResponse baseResponse = catalogService.deleteService(id);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_service")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateService(@Valid @RequestBody ServiceTypeData serviceTypeData){
        BaseResponse baseResponse = catalogService.updateService(serviceTypeData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/list_blog")
    public ResponseEntity <BaseResponse> listBlog(){
        BaseResponse baseResponse = catalogService.listBlogPosts();
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @GetMapping("/single_blog")
    public ResponseEntity <BaseResponse> singleBlog(@RequestParam("id") String id){
        BaseResponse baseResponse = catalogService.singleBlogPost(id);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/create_blog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> createBlog(@Valid @RequestBody BlogPostData blogPostData){
        BaseResponse baseResponse = catalogService.createBlogPost(blogPostData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/update_blog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> updateBlog(@Valid @RequestBody BlogPostData blogPostData){
        BaseResponse baseResponse = catalogService.updateBlogPost(blogPostData);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }

    @PostMapping("/delete_blog")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity <BaseResponse> deleteBlog(@RequestBody Map<String, String> body){
        String id = body.getOrDefault("id", "");
        BaseResponse baseResponse = catalogService.deleteBlogPost(id);
        HttpStatus status = ApiResponses.httpStatus(baseResponse);
        return new ResponseEntity<>(baseResponse,status);
    }
}
