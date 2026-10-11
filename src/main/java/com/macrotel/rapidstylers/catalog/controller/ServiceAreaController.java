package com.macrotel.rapidstylers.catalog.controller;

import com.macrotel.rapidstylers.catalog.service.ServiceAreaService;
import com.macrotel.rapidstylers.common.web.ApiResponses;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.CurrentAccount;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Where RapidStylers works: the public list, and the admin's list with add, edit and remove. */
@RestController
@RequestMapping("/rapid_stylers")
public class ServiceAreaController {

    @Autowired
    ServiceAreaService serviceAreaService;

    @GetMapping("/service_areas")
    public ResponseEntity<BaseResponse> publicAreas() {
        return ApiResponses.respond(serviceAreaService.publicAreas());
    }

    @GetMapping("/admin/service_areas")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> adminAreas(HttpServletRequest request) {
        if (CurrentAccount.adminId(request) == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(serviceAreaService.adminAreas());
    }

    /** Body: {id?, province, city?, active?, note?}. No id adds a new area; city blank covers the whole province. */
    @PostMapping("/admin/save_service_area")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        Object active = body.get("active");
        return ApiResponses.respond(serviceAreaService.save(adminId, id(body), text(body.get("province")),
                text(body.get("city")), active == null ? null : Boolean.valueOf(String.valueOf(active)), text(body.get("note"))));
    }

    /** Body: {id}. */
    @PostMapping("/admin/delete_service_area")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BaseResponse> delete(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String adminId = CurrentAccount.adminId(request);
        if (adminId == null) return CurrentAccount.unauthorized();
        return ApiResponses.respond(serviceAreaService.delete(adminId, id(body)));
    }

    private static Long id(Map<String, Object> body) {
        Object raw = body.get("id");
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
