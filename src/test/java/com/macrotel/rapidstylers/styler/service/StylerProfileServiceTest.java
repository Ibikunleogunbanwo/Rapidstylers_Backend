package com.macrotel.rapidstylers.styler.service;

import com.macrotel.rapidstylers.catalog.entity.ServiceEntity;
import com.macrotel.rapidstylers.catalog.repo.ServiceRepo;
import com.macrotel.rapidstylers.common.service.AuditService;
import com.macrotel.rapidstylers.common.service.ReadCacheService;
import com.macrotel.rapidstylers.common.web.BaseResponse;
import com.macrotel.rapidstylers.customer.entity.UserEntity;
import com.macrotel.rapidstylers.customer.repo.UserRepo;
import com.macrotel.rapidstylers.media.service.CloudinaryService;
import com.macrotel.rapidstylers.media.service.ImageReclaimService;
import com.macrotel.rapidstylers.media.service.UploadOwnership;
import com.macrotel.rapidstylers.styler.entity.StylerEntity;
import com.macrotel.rapidstylers.styler.pojo.StylerBusinessInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerPersonalInfoData;
import com.macrotel.rapidstylers.styler.pojo.StylerPhotosData;
import com.macrotel.rapidstylers.styler.repo.StylerRepo;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A stylist edits only their own profile, and what they save is checked and kept. */
class StylerProfileServiceTest {

    private StylerProfileService profiles;
    private StylerRepo stylerRepo;
    private StylerEntity chloe;
    private final UploadOwnership ownership = new UploadOwnership();

    @BeforeEach
    void setUp() {
        profiles = new StylerProfileService();
        stylerRepo = mock(StylerRepo.class);
        profiles.stylerRepo = stylerRepo;
        profiles.userRepo = mock(UserRepo.class);
        profiles.serviceRepo = mock(ServiceRepo.class);
        profiles.geocodingService = mock(GeocodingService.class);
        profiles.googleTimezoneService = mock(GoogleTimezoneService.class);
        profiles.locationCacheService = mock(LocationCacheService.class);
        profiles.readCacheService = mock(ReadCacheService.class);
        profiles.imageReclaimService = mock(ImageReclaimService.class);
        profiles.cloudinaryService = mock(CloudinaryService.class);
        profiles.uploadOwnership = ownership;
        profiles.auditService = new AuditService();

        chloe = new StylerEntity();
        chloe.setStylerId("CM1234");
        chloe.setFirstname("Chloe");
        chloe.setLastname("Martin");
        chloe.setPhoneNumber("5875550100");
        chloe.setEmailAddress("chloe@example.com");
        chloe.setStreetAddress("10 Main St");
        chloe.setCity("Calgary");
        chloe.setProvince("Alberta");
        chloe.setCountry("Canada");
        chloe.setLatitude(51.0);
        chloe.setLongitude(-114.0);
        chloe.setTimeZone("America/Edmonton");
        chloe.setTimeZoneSource("google");
        chloe.setProfileImageUrl("https://res.cloudinary.com/x/image/upload/rapid_stylers/profile/old.jpg");
        when(stylerRepo.findByStylerId("CM1234")).thenReturn(Optional.of(chloe));
        when(profiles.serviceRepo.findById(3L)).thenReturn(Optional.of(new ServiceEntity()));
    }

    private StylerBusinessInfoData business() {
        StylerBusinessInfoData data = new StylerBusinessInfoData();
        data.setBusinessName("Chloé Hair");
        data.setDescription("Braids and cuts");
        data.setServiceTypeId("3");
        data.setStreetAddress("10 Main St");
        data.setCity("Calgary");
        data.setProvince("Alberta");
        data.setCountry("Canada");
        return data;
    }

    @Test
    void personalInfoIsSavedAndTheSavedProfileReturned() {
        StylerPersonalInfoData data = new StylerPersonalInfoData();
        data.setFirstname("Chloé");
        data.setLastname("Martin-Lee");
        data.setPhoneNumber("4035550199");

        BaseResponse response = profiles.updatePersonalInfo("CM1234", data);

        assertEquals("200", response.getStatusCode());
        assertEquals("Martin-Lee", chloe.getLastname());
        assertEquals("4035550199", ((Map<?, ?>) response.getData()).get("phoneNumber"));
        verify(stylerRepo).save(chloe);
    }

    @Test
    void aPhoneNumberSomeoneElseHasIsRefused() {
        when(profiles.userRepo.findByPhoneNumber("4035550199")).thenReturn(Optional.of(new UserEntity()));
        StylerPersonalInfoData data = new StylerPersonalInfoData();
        data.setFirstname("Chloe");
        data.setLastname("Martin");
        data.setPhoneNumber("4035550199");

        BaseResponse response = profiles.updatePersonalInfo("CM1234", data);

        assertEquals("400", response.getStatusCode());
        assertEquals("5875550100", chloe.getPhoneNumber());
    }

    @Test
    void businessDetailsWithoutAMoveKeepTheLocation() {
        BaseResponse response = profiles.updateBusinessInfo("CM1234", business());

        assertEquals("200", response.getStatusCode());
        assertEquals("Chloé Hair", chloe.getBusinessName());
        verify(profiles.geocodingService, never()).geocode(anyString());
        assertEquals("America/Edmonton", chloe.getTimeZone());
    }

    @Test
    void aNewAddressIsLocatedAndTheTimeZoneFollowsIt() {
        StylerBusinessInfoData data = business();
        data.setStreetAddress("1 Bay St");
        data.setCity("Toronto");
        data.setProvince("Ontario");
        when(profiles.geocodingService.geocode(anyString())).thenReturn(Map.of("latitude", 43.6, "longitude", -79.4));
        when(profiles.googleTimezoneService.timeZoneId(43.6, -79.4)).thenReturn("America/Toronto");

        BaseResponse response = profiles.updateBusinessInfo("CM1234", data);

        assertEquals("200", response.getStatusCode());
        assertEquals(43.6, chloe.getLatitude());
        assertEquals("America/Toronto", chloe.getTimeZone());
        verify(profiles.locationCacheService).indexStyler("CM1234", -79.4, 43.6);
    }

    @Test
    void anAddressThatCannotBeFoundIsRefused() {
        StylerBusinessInfoData data = business();
        data.setStreetAddress("Nowhere");

        BaseResponse response = profiles.updateBusinessInfo("CM1234", data);

        assertEquals("400", response.getStatusCode());
        assertEquals("10 Main St", chloe.getStreetAddress());
    }

    @Test
    void aChosenTimeZoneStaysTheStylistsAndAnInvalidOneIsRefused() {
        StylerBusinessInfoData data = business();
        data.setTimeZone("America/Vancouver");
        profiles.updateBusinessInfo("CM1234", data);
        assertEquals("America/Vancouver", chloe.getTimeZone());
        assertEquals("stylist", chloe.getTimeZoneSource());
        assertEquals(true, ((Map<?, ?>) profiles.profile("CM1234").getData()).get("timeZoneChosenByStylist"));

        data.setTimeZone("Mars/Olympus");
        assertEquals("400", profiles.updateBusinessInfo("CM1234", data).getStatusCode());
        data.setTimeZone("+05:00");
        assertEquals("400", profiles.updateBusinessInfo("CM1234", data).getStatusCode());
        assertEquals("America/Vancouver", chloe.getTimeZone());
    }

    @Test
    void anUnknownServiceTypeIsRefused() {
        StylerBusinessInfoData data = business();
        data.setServiceTypeId("99");

        assertEquals("400", profiles.updateBusinessInfo("CM1234", data).getStatusCode());
    }

    private String upload(String owner, String file) {
        String publicId = "rapid_stylers/profile/" + ownership.folderFor("STYLER", owner) + "/" + file;
        String url = "https://res.cloudinary.com/x/image/upload/v1/" + publicId + ".jpg";
        when(profiles.cloudinaryService.extractPublicId(url)).thenReturn(publicId);
        return url;
    }

    @Test
    void ownPhotosAreSavedAndTheOldOneGivenBack() {
        StylerPhotosData data = new StylerPhotosData();
        String newProfile = upload("CM1234", "new");
        data.setProfileImageUrl(newProfile);
        data.setCoverImageUrl(upload("CM1234", "cover"));

        BaseResponse response = profiles.updatePhotos("CM1234", data);

        assertEquals("200", response.getStatusCode());
        assertEquals(newProfile, chloe.getProfileImageUrl());
        assertTrue(chloe.getCoverImageUrl().contains("/cover"));
        verify(profiles.imageReclaimService).reclaimIfUnreferenced("https://res.cloudinary.com/x/image/upload/rapid_stylers/profile/old.jpg");
    }

    @Test
    void someoneElsesPhotoCannotBeUsed() {
        StylerPhotosData data = new StylerPhotosData();
        data.setCoverImageUrl(upload("XY9999", "theirs"));

        BaseResponse response = profiles.updatePhotos("CM1234", data);

        assertEquals("400", response.getStatusCode());
        assertFalse(chloe.getCoverImageUrl() != null);
        verify(stylerRepo, never()).save(chloe);
    }

    @Test
    void onlyPlaceZonesAreAccepted() {
        assertTrue(StylerProfileService.isRegionZone("America/Edmonton"));
        assertFalse(StylerProfileService.isRegionZone("UTC"));
        assertFalse(StylerProfileService.isRegionZone("America/Nowhere"));
    }
}
