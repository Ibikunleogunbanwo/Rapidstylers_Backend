package com.macrotel.rapidstylers.catalog.service;

import com.macrotel.rapidstylers.catalog.entity.ServiceAreaEntity;
import com.macrotel.rapidstylers.catalog.repo.ServiceAreaRepo;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.service.StylerRecords;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Where RapidStylers works. Picture a map with some provinces shaded in whole
 * and some cities pinned. A stylist whose profile sits inside a shaded province
 * or on a pinned city can be found and booked; anyone outside stays invisible
 * to customers until the admin adds their area. With nothing shaded at all,
 * the whole map counts, so a fresh install behaves exactly as before.
 *
 * <p>The active areas are held in memory and re-read at most once a minute
 * (or straight after an admin change), because every search asks this for
 * every stylist it lists.
 */
@Service
public class ServiceAreaService {

    /** Canonical province names, as profiles write them. */
    public static final List<String> PROVINCES = List.of("Alberta", "British Columbia", "Manitoba", "New Brunswick",
            "Newfoundland and Labrador", "Northwest Territories", "Nova Scotia", "Nunavut", "Ontario",
            "Prince Edward Island", "Quebec", "Saskatchewan", "Yukon");
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("ab", "Alberta"), Map.entry("bc", "British Columbia"), Map.entry("mb", "Manitoba"),
            Map.entry("nb", "New Brunswick"), Map.entry("nl", "Newfoundland and Labrador"),
            Map.entry("nt", "Northwest Territories"), Map.entry("ns", "Nova Scotia"), Map.entry("nu", "Nunavut"),
            Map.entry("on", "Ontario"), Map.entry("pe", "Prince Edward Island"), Map.entry("qc", "Quebec"),
            Map.entry("sk", "Saskatchewan"), Map.entry("yt", "Yukon"));
    private static final long REFRESH_MILLIS = 60_000;

    @Autowired ServiceAreaRepo serviceAreaRepo;
    @Autowired StylerRecords stylerRecords;
    @Autowired AuditService auditService;
    @Autowired(required = false) ReadCacheService readCacheService;

    /** Snapshot: lower-case "province|" for whole provinces and "province|city" for cities. Empty: everywhere. */
    private volatile Set<String> active;
    private volatile long loadedAt;

    /** Can a stylist at this province and city be found and booked? */
    public boolean covers(String province, String city) {
        Set<String> areas = activeAreas();
        if (areas.isEmpty()) return true;
        String p = canonicalProvince(province);
        if (p == null) return false;
        if (areas.contains(key(p, null))) return true;
        return city != null && !city.isBlank() && areas.contains(key(p, city));
    }

    public boolean covers(StylerEntity stylist) {
        return stylist != null && covers(stylist.getProvince(), stylist.getCity());
    }

    /** For customers: {restricted, areas: [{province, wholeProvince, cities}]}; restricted false means everywhere. */
    public BaseResponse publicAreas() {
        List<ServiceAreaEntity> rows = serviceAreaRepo.findByActiveTrue();
        Map<String, Map<String, Object>> byProvince = new TreeMap<>();
        for (ServiceAreaEntity a : rows) {
            Map<String, Object> entry = byProvince.computeIfAbsent(a.getProvince(), p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("province", p);
                m.put("wholeProvince", false);
                m.put("cities", new TreeSet<String>());
                return m;
            });
            if (a.getCity() == null) entry.put("wholeProvince", true);
            else castCities(entry).add(a.getCity());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("restricted", !rows.isEmpty());
        data.put("areas", new ArrayList<>(byProvince.values()));
        return ok(new BaseResponse(true), data);
    }

    /** For the admin: every area with how many approved stylists it holds, and the approved stylists no area covers. */
    public BaseResponse adminAreas() {
        List<ServiceAreaEntity> rows = serviceAreaRepo.findAll();
        rows.sort(Comparator.comparing(ServiceAreaEntity::getProvince).thenComparing(a -> a.getCity() == null ? "" : a.getCity()));
        List<StylerEntity> approved = stylerRecords.findAll().stream()
                .filter(s -> VERIFICATION_APPROVED.equals(s.getVerificationStatus())).collect(Collectors.toList());
        List<Map<String, Object>> items = new ArrayList<>();
        for (ServiceAreaEntity a : rows) {
            Map<String, Object> m = row(a);
            m.put("approvedStylists", approved.stream().filter(s -> inside(a, s)).count());
            items.add(m);
        }
        List<Map<String, Object>> outside = approved.stream().filter(s -> !covers(s)).map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stylerId", s.getStylerId());
            m.put("businessName", s.getBusinessName());
            m.put("province", s.getProvince());
            m.put("city", s.getCity());
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("restricted", !activeAreas().isEmpty());
        data.put("areas", items);
        data.put("approvedStylistsOutside", outside);
        data.put("provinces", PROVINCES);
        return ok(new BaseResponse(true), data);
    }

    /** Adds an area (id null) or edits one. City blank: the whole province. */
    public BaseResponse save(String adminId, Long id, String province, String city, Boolean active, String note) {
        BaseResponse response = new BaseResponse(true);
        String p = canonicalProvince(province);
        if (p == null) {
            response.setMessage("Choose a Canadian province or territory");
            return response;
        }
        String c = city == null || city.isBlank() ? null : city.trim().replaceAll("\\s+", " ");
        if (c != null && c.length() > 100) {
            response.setMessage("Keep the city under 100 characters");
            return response;
        }
        if (note != null && note.length() > 500) {
            response.setMessage("Keep the note under 500 characters");
            return response;
        }
        ServiceAreaEntity area;
        if (id == null) {
            area = new ServiceAreaEntity();
        } else {
            Optional<ServiceAreaEntity> found = serviceAreaRepo.findById(id);
            if (found.isEmpty()) {
                response.setStatusCode("404");
                response.setMessage("Area not found");
                return response;
            }
            area = found.get();
        }
        String areaKey = key(p, c);
        Optional<ServiceAreaEntity> clash = serviceAreaRepo.findByAreaKey(areaKey);
        if (clash.isPresent() && !clash.get().getId().equals(area.getId())) {
            response.setMessage(c == null ? p + " is already on the list" : c + ", " + p + " is already on the list");
            return response;
        }
        area.setProvince(p);
        area.setCity(c);
        area.setAreaKey(areaKey);
        area.setActive(active == null || active);
        area.setNote(note == null || note.isBlank() ? null : note.trim());
        area.setUpdatedBy(adminId);
        area.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        serviceAreaRepo.save(area);
        changed();
        auditService.audit(adminId, "ADMIN", "ADMIN_SERVICE_AREA_SAVED", "SERVICE_AREA", "area:" + area.getId(),
                areaKey + " active=" + area.isActive());
        return ok(response, row(area));
    }

    public BaseResponse delete(String adminId, Long id) {
        BaseResponse response = new BaseResponse(true);
        Optional<ServiceAreaEntity> found = id == null ? Optional.empty() : serviceAreaRepo.findById(id);
        if (found.isEmpty()) {
            response.setStatusCode("404");
            response.setMessage("Area not found");
            return response;
        }
        serviceAreaRepo.delete(found.get());
        changed();
        auditService.audit(adminId, "ADMIN", "ADMIN_SERVICE_AREA_DELETED", "SERVICE_AREA", "area:" + id, found.get().getAreaKey());
        return ok(response, EMPTY_DATA);
    }

    /** "AB", "alberta " and "Alberta" all mean Alberta; anything else is null. */
    public static String canonicalProvince(String province) {
        if (province == null) return null;
        String k = province.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return null;
        if (ABBREVIATIONS.containsKey(k)) return ABBREVIATIONS.get(k);
        for (String p : PROVINCES) if (p.toLowerCase(Locale.ROOT).equals(k)) return p;
        return null;
    }

    private Set<String> activeAreas() {
        Set<String> snapshot = active;
        if (snapshot == null || System.currentTimeMillis() - loadedAt > REFRESH_MILLIS) {
            snapshot = serviceAreaRepo.findByActiveTrue().stream().map(ServiceAreaEntity::getAreaKey).collect(Collectors.toSet());
            active = snapshot;
            loadedAt = System.currentTimeMillis();
        }
        return snapshot;
    }

    /** Re-read now, and drop cached search results and profiles so the change shows at once. */
    private void changed() {
        active = null;
        if (readCacheService != null) {
            readCacheService.evictPrefix("search:");
            readCacheService.evictPrefix(ReadCacheService.KEY_STYLER_DTO);
        }
    }

    private static boolean inside(ServiceAreaEntity a, StylerEntity s) {
        String p = canonicalProvince(s.getProvince());
        if (p == null || !p.equals(a.getProvince())) return false;
        return a.getCity() == null || (s.getCity() != null && a.getCity().equalsIgnoreCase(s.getCity().trim()));
    }

    static String key(String province, String city) {
        return (province.toLowerCase(Locale.ROOT) + "|" + (city == null ? "" : city.trim().toLowerCase(Locale.ROOT)));
    }

    private static Map<String, Object> row(ServiceAreaEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("province", a.getProvince());
        m.put("city", a.getCity());
        m.put("wholeProvince", a.getCity() == null);
        m.put("active", a.isActive());
        m.put("note", a.getNote());
        m.put("updatedBy", a.getUpdatedBy());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> castCities(Map<String, Object> entry) {
        return (Set<String>) entry.get("cities");
    }

    private static BaseResponse ok(BaseResponse response, Object data) {
        response.setStatusCode(SUCCESS_STATUS_CODE);
        response.setMessage(SUCCESS_MESSAGE);
        response.setData(data);
        return response;
    }
}
