package com.macrotel.rapidstylers.styler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.macrotel.rapidstylers.booking.repo.BookAppointmentRepo;
import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.common.web.Responses;
import com.macrotel.rapidstylers.styler.dto.StylerAccountDTO;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Locale;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.macrotel.rapidstylers.config.AppConstants.*;

/**
 * Finding stylists: lists, search by name, city or province, and nearby search.
 */
@Service
public class SearchService {
    private static final Logger LOG = Logger.getLogger(SearchService.class.getName());
    @Autowired
    ServiceRepo serviceRepo;
    @Autowired
    StylerRepo stylerRepo;
    @Autowired
    BookAppointmentRepo bookAppointmentRepo;
    @Autowired
    GeocodingService geocodingService;
    @Autowired
    LocationCacheService locationCacheService;
    @Autowired
    LocationService locationService;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    ReadCacheService readCacheService;
    @Autowired
    AvailabilityService availabilityService;
    @Autowired
    StylerEligibility stylerEligibility;
    @Autowired
    StylerService stylerService;
    @Autowired(required = false)
    StylerPresence stylerPresence;

    public BaseResponse listAllStylers(){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerEntity> getAllStylers = stylerRepo.findAll();
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getAllStylers){
                // A public list is a customer-facing surface, so it uses the
                // same gate as search: approval alone would advertise a profile
                // that cannot be booked.
                if(stylerEligibility.isBookableStyler(stylerEntity)){
                    result.add(stylerService.listRowWithHours(stylerService.cachedStylerAccountDTO(stylerEntity), stylerEntity.getStylerId()));
                }
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse searchStyler(String businessName){
        BaseResponse response = new BaseResponse(true);
        try{
            List<StylerEntity> getStylerByName = stylerRepo.searchStyler(businessName);
            List<Object> result = new ArrayList<>();
            for(StylerEntity stylerEntity : getStylerByName){
                if(stylerEligibility.isBookableStyler(stylerEntity)){
                    result.add(stylerService.listRowWithHours(stylerService.cachedStylerAccountDTO(stylerEntity), stylerEntity.getStylerId()));
                }
            }
            Collections.reverse(result);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse searchStylerByProvince(String province){
        BaseResponse response = new BaseResponse(true);
        try{
            // Trim defensively so padded values (" Alberta ") still match.
            province = province == null ? "" : province.trim();
            final String provinceKey = province;
            List<Object> result = readCacheService.getOrLoad(
                    ReadCacheService.KEY_SEARCH_PROVINCE + provinceKey,
                    ReadCacheService.SEARCH_LIST_TTL, ReadCacheService.SEARCH_LIST_JITTER,
                    () -> {
                        List<StylerEntity> getStylerByProvince = stylerRepo.findByProvinceIgnoreCase(provinceKey);
                        List<Object> built = new ArrayList<>();
                        for(StylerEntity stylerEntity : getStylerByProvince){
                            if(stylerEligibility.isBookableStyler(stylerEntity)){
                                built.add(stylerService.listRowWithHours(stylerService.cachedStylerAccountDTO(stylerEntity), stylerEntity.getStylerId()));
                            }
                        }
                        Collections.reverse(built);
                        return built;
                    });
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    /**
     * City search for the results page. When the city has no professionals, the
     * search widens to the city's province so the page never shows an empty
     * grid it could have filled; the "widened" data flag lets the frontend say
     * so honestly instead of quietly relabelling the results.
     */
    public BaseResponse searchStylerByCity(String city){
        BaseResponse response = new BaseResponse(true);
        try{
            // Trim defensively so padded values (" Calgary ") still match and
            // whitespace-only payloads return an empty list, not every styler.
            city = city == null ? "" : city.trim();
            final String cityKey = city;
            List<Object> cityResult = readCacheService.getOrLoad(
                    ReadCacheService.KEY_SEARCH_CITY + cityKey,
                    ReadCacheService.SEARCH_LIST_TTL, ReadCacheService.SEARCH_LIST_JITTER,
                    () -> {
                        List<StylerEntity> cityStylers = cityKey.isEmpty()
                                ? new ArrayList<>()
                                : stylerRepo.findByCityIgnoreCase(cityKey);
                        List<Object> built = new ArrayList<>();
                        for(StylerEntity stylerEntity : cityStylers){
                            if(stylerEligibility.isBookableStyler(stylerEntity)){
                                built.add(stylerService.listRowWithHours(stylerService.cachedStylerAccountDTO(stylerEntity), stylerEntity.getStylerId()));
                            }
                        }
                        Collections.reverse(built);
                        return built;
                    });
            if(cityResult == null || cityResult.isEmpty()){
                // Widen: same query but for the city's province, when known.
                String province = cityKey.isEmpty() ? null : stylerRepo.findByCityIgnoreCase(cityKey).stream()
                        .map(StylerEntity::getProvince)
                        .filter(p -> p != null && !p.isBlank())
                        .findFirst()
                        .orElse(null);
                if(province != null){
                    List<Object> widened = readCacheService.getOrLoad(
                            ReadCacheService.KEY_SEARCH_PROVINCE + province,
                            ReadCacheService.SEARCH_LIST_TTL, ReadCacheService.SEARCH_LIST_JITTER,
                            () -> {
                                List<StylerEntity> provinceStylers = stylerRepo.findByProvinceIgnoreCase(province);
                                List<Object> built = new ArrayList<>();
                                for(StylerEntity stylerEntity : provinceStylers){
                                    if(stylerEligibility.isBookableStyler(stylerEntity)){
                                        built.add(stylerService.listRowWithHours(stylerService.cachedStylerAccountDTO(stylerEntity), stylerEntity.getStylerId()));
                                    }
                                }
                                Collections.reverse(built);
                                return built;
                            });
                    // Same envelope shape as the paginated nearby search: a map
                    // with "items", so clients unwrap one of two known shapes.
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("items", widened);
                    payload.put("widened", true);
                    payload.put("widenedProvince", province);
                    response.setStatusCode(SUCCESS_STATUS_CODE);
                    response.setMessage(SUCCESS_MESSAGE);
                    response.setData(payload);
                    return response;
                }
            }
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(cityResult == null ? EMPTY_DATA : cityResult);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse getStylerByService(String serviceId){
        BaseResponse response = new BaseResponse(true);
        try{
            Optional<ServiceEntity> isServiceIdExist = serviceRepo.findById(Long.valueOf(serviceId));
            if(isServiceIdExist.isEmpty()){
                response.setStatusCode(ERROR_STATUS_CODE);
                response.setMessage("Invalid service Id");
                response.setData(EMPTY_DATA);
                return response;
            }
            List<Object> result = readCacheService.getOrLoad(
                    ReadCacheService.KEY_SEARCH_SERVICE + serviceId,
                    ReadCacheService.SEARCH_LIST_TTL, ReadCacheService.SEARCH_LIST_JITTER,
                    () -> {
                        List<StylerEntity> getStylerData = stylerRepo.findByServiceTypeId(serviceId);
                        List<Object> built = new ArrayList<>();
                        for(StylerEntity stylerEntity : getStylerData){
                            if(stylerEligibility.isBookableStyler(stylerEntity)){
                                built.add(stylerService.listRowWithHours(stylerService.cachedStylerAccountDTO(stylerEntity), stylerEntity.getStylerId()));
                            }
                        }
                        return built;
                    });

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    // ── Location-based search (Redis geospatial) ────────────────────────

    public BaseResponse searchNearby(double longitude, double latitude, double radius, String serviceTypeId, String city, String requestedDate, String requestedTime, int requestedDurationMinutes, boolean openNow){
        return searchNearby(longitude, latitude, radius, serviceTypeId, city, requestedDate, requestedTime, requestedDurationMinutes, openNow, null, null);
    }

    public BaseResponse searchNearby(double longitude, double latitude, double radius, String serviceTypeId, String city, String requestedDate, String requestedTime, int requestedDurationMinutes, boolean openNow, Integer requestedPage, Integer requestedPageSize){
        BaseResponse response = searchNearby(longitude, latitude, radius, serviceTypeId, city);
        if(!"200".equals(response.getStatusCode()) || !(response.getData() instanceof List)) return response;
        try {
            LocalDate requested = requestedDate == null || requestedDate.isBlank() ? null : availabilityService.parseBookingDate(requestedDate);
            LocalTime requestedStart = requestedTime == null || requestedTime.isBlank() ? null : availabilityService.parseBookingTime(requestedTime);
            int duration = requestedDurationMinutes <= 0 ? DEFAULT_SERVICE_DURATION_MINUTES : requestedDurationMinutes;
            List<StylerAccountDTO> candidates = new ArrayList<>();
            for(Object item : (List<?>) response.getData()){
                if(!(item instanceof StylerAccountDTO)) continue;
                StylerAccountDTO dto = (StylerAccountDTO) item;
                Optional<StylerEntity> styler = stylerRepo.findByStylerId(dto.getStylerId());
                if(styler.isEmpty() || !stylerEligibility.isBookableStyler(styler.get())) continue;
                if(requested != null && requestedStart != null && (!availabilityService.isDateNotException(dto.getStylerId(), requested.toString())
                        || !availabilityService.timeWithinAvailability(dto.getStylerId(), requested.toString(), requestedStart.toString(), duration)
                        || !availabilityService.isWindowFree(dto.getStylerId(), requested.toString(), requestedStart.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)), duration))) continue;
                // "Open now" means the vendor's own clock: their hours live in
                // their business-address zone, so a Toronto vendor must read
                // open while the Edmonton server clock says otherwise. The
                // stored zone wins; the province map covers rows without one.
                String storedZone = styler.get().getTimeZone();
                ZoneId vendorZone = (storedZone != null && !storedZone.isBlank())
                        ? ZoneId.of(storedZone)
                        : VendorZoneResolver.zoneForProvince(styler.get().getProvince());
                if(openNow && !availabilityService.isOpenAt(dto.getStylerId(), ZonedDateTime.now(vendorZone).toLocalDate(), ZonedDateTime.now(vendorZone).toLocalTime(), 30)) continue;
                candidates.add(dto);
            }
            candidates.sort((left, right) -> Double.compare(searchScore(right), searchScore(left)));
            if (requestedPage != null || requestedPageSize != null) {
                int page = requestedPage == null || requestedPage < 1 ? 1 : requestedPage;
                int pageSize = requestedPageSize == null || requestedPageSize < 1 ? 20 : Math.min(requestedPageSize, 50);
                int from = Math.min((page - 1) * pageSize, candidates.size());
                int to = Math.min(from + pageSize, candidates.size());
                Map<String, Object> paged = new LinkedHashMap<>();
                paged.put("items", candidates.subList(from, to));
                paged.put("page", page);
                paged.put("pageSize", pageSize);
                paged.put("total", candidates.size());
                paged.put("hasNext", to < candidates.size());
                response.setData(paged);
            } else {
                response.setData(candidates);
            }
        } catch(Exception ex){
            return Responses.errorResponse(response, "Invalid search date or time");
        }
        return response;
    }

    public BaseResponse searchNearby(double longitude, double latitude, double radius, String serviceTypeId, String city){
        BaseResponse response = new BaseResponse(true);
        try{
            // Step 1: Redis geospatial search (returns stylerId → distance in km)
            Map<String, Double> redisResults = locationCacheService.findNearbyWithDistance(longitude, latitude, radius);

            // Collect all results: stylerId → distance (null for DB-only results)
            Map<String, Double> allStylerDistances = new LinkedHashMap<>();
            for(Map.Entry<String, Double> entry : redisResults.entrySet()){
                allStylerDistances.put(entry.getKey(), entry.getValue());
            }

            // Step 2: DB city search — catches stylers not yet in Redis
            Set<String> seenIds = new HashSet<>(allStylerDistances.keySet());
            // Trim defensively so padded values (" Calgary ") still match and
            // whitespace-only payloads are ignored, not passed to the repo.
            if(city != null) city = city.trim();
            if(city != null && !city.isEmpty()){
                List<StylerEntity> cityStylers;
                if(serviceTypeId != null && !serviceTypeId.isEmpty()){
                    cityStylers = stylerRepo.findByCityAndServiceType(city, serviceTypeId);
                } else {
                    cityStylers = stylerRepo.findByCityIgnoreCase(city);
                }
                for(StylerEntity styler : cityStylers){
                    if(!stylerEligibility.isBookableStyler(styler)) continue;
                    if(!seenIds.contains(styler.getStylerId())){
                        // Compute Haversine distance for DB results
                        double dist = haversine(latitude, longitude, styler.getLatitude(), styler.getLongitude());
                        allStylerDistances.put(styler.getStylerId(), dist);
                        seenIds.add(styler.getStylerId());
                    }
                }
            }

            if(allStylerDistances.isEmpty()){
                response.setStatusCode(SUCCESS_STATUS_CODE);
                response.setMessage("No professionals found in this area");
                response.setData(EMPTY_DATA);
                return response;
            }

            // Step 3: Sort by distance (nearest first)
            List<Map.Entry<String, Double>> sorted = new ArrayList<>(allStylerDistances.entrySet());
            sorted.sort(Map.Entry.comparingByValue());

            // Step 4: Fetch all matching stylers in ONE batch query (kills the
            // per-styler N+1), then build DTOs through the read cache.
            List<String> stylerIds = new ArrayList<>();
            for(Map.Entry<String, Double> entry : sorted){
                stylerIds.add(entry.getKey());
            }
            Map<String, StylerEntity> stylersById = new HashMap<>();
            for(StylerEntity styler : stylerRepo.findByStylerIdIn(stylerIds)){
                stylersById.put(styler.getStylerId(), styler);
            }
            List<Object> result = new ArrayList<>();
            for(Map.Entry<String, Double> entry : sorted){
                String stylerId = entry.getKey();
                double distKm = entry.getValue();
                StylerEntity styler = stylersById.get(stylerId);
                if(styler == null) continue;
                if(!stylerEligibility.isBookableStyler(styler)) continue;
                if(serviceTypeId != null && !serviceTypeId.isEmpty()){
                    if(!serviceTypeId.equals(styler.getServiceTypeId())) continue;
                }
                // Copy the shared cached DTO before stamping this result's
                // distance so concurrent searches never corrupt each other.
                StylerAccountDTO dto = objectMapper.convertValue(stylerService.cachedStylerAccountDTO(styler), StylerAccountDTO.class);
                dto.setDistanceKm(Math.round(distKm * 10.0) / 10.0);
                if(stylerPresence != null) stylerPresence.stamp(dto);
                // Same copy carries the trading hours, so a nearby result states
                // real opening hours rather than a bare presence badge.
                stylerService.attachHours(dto, stylerId);
                result.add(dto);
            }

            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(result);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    private double searchScore(StylerAccountDTO dto){
        double distance = dto.getDistanceKm() == null ? 1000.0 : dto.getDistanceKm();
        double rating = dto.getAverageRating() == null ? 0.0 : dto.getAverageRating();
        long reviews = dto.getReviewCount() == null ? 0L : dto.getReviewCount();
        long completed = bookAppointmentRepo.findByStylerId(dto.getStylerId()).stream().filter(a -> "0".equals(a.getStatus())).count();
        double coldStartBoost = reviews == 0 ? 1.0 : 0.0;
        return (rating * 20.0) + Math.min(completed, 100) * 0.25 + coldStartBoost - Math.min(distance, 1000) * 0.35;
    }

    private double haversine(double lat1, double lng1, double lat2, double lng2){
        return GeoDistance.km(lat1, lng1, lat2, lng2);
    }

    public BaseResponse detectLocation(javax.servlet.http.HttpServletRequest request){
        BaseResponse response = new BaseResponse(true);
        try{
            Map<String, Object> location = locationService.detectLocation(request);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(location);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }

    public BaseResponse reverseGeocode(double lat, double lng){
        BaseResponse response = new BaseResponse(true);
        try{
            Map<String, Object> geo = geocodingService.reverseGeocode(lat, lng);
            response.setStatusCode(SUCCESS_STATUS_CODE);
            response.setMessage(SUCCESS_MESSAGE);
            response.setData(geo);
        }
        catch (Exception ex){
            LOG.warning(ex.getMessage());
        }
        return response;
    }
}
